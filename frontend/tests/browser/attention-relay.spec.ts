import { expect, test } from '@playwright/test';
import { readFile, writeFile } from 'node:fs/promises';

test.beforeEach(async ({ page }) => {
  const path = process.env.HERDR_ATTENTION_CREDENTIALS;
  if (!path) return;
  try {
    const credential = await readFile(path, 'utf8');
    await page.addInitScript(value => localStorage.setItem('herdr_device_auth_v1', value), credential);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }
});

test.afterEach(async ({ page }) => {
  const path = process.env.HERDR_ATTENTION_CREDENTIALS;
  if (!path || page.isClosed()) return;
  const credential = await page.evaluate(() => localStorage.getItem('herdr_device_auth_v1'));
  if (credential) await writeFile(path, credential, { mode: 0o600 });
});

interface FakeOperation {
  argv?: string[];
  params?: { pane_id?: string };
}

async function operations(path = process.env.HERDR_ATTENTION_OPERATIONS): Promise<FakeOperation[]> {
  if (!path) throw new Error('HERDR_ATTENTION_OPERATIONS is not configured');
  try {
    const content = await readFile(path, 'utf8');
    return content.trim().split('\n').filter(Boolean).map((line) => JSON.parse(line));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return [];
    throw error;
  }
}

test('drives captured attention panes through the real relay', async ({ page }) => {
  const wsURL = process.env.HERDR_ATTENTION_WS_URL;
  if (!wsURL) throw new Error('HERDR_ATTENTION_WS_URL is not configured');
  // Both browsers share one relay fixture. Question keys are sent again on every run, but each
  // approval is answered once, so a later run gets the first answer back without new keys.
  const earlier = (await operations()).length;
  const sentByThisRun = async () => (await operations()).slice(earlier);
  const approvalsSent = async (argv: string[]) => (await operations())
    .filter((operation) => JSON.stringify(operation.argv) === JSON.stringify(argv)).length;
  await page.addInitScript(({ relayURL }) => {
    localStorage.setItem('herdr_relays', JSON.stringify([{
      id: 'captured-attention',
      label: 'Captured relay',
      url: relayURL,
      token: 'attention-browser-test-key-32byt',
    }]));
  }, { relayURL: wsURL });
  await page.goto('/');

  const approvalCard = page.locator('.agent-card').filter({ hasText: 'qoder-approval' });
  await expect(approvalCard.getByRole('button', { name: 'Allow once', exact: true })).toBeVisible();
  await approvalCard.getByRole('button', { name: /Open qoder-approval on Captured relay/ }).click();
  await expect(page.getByRole('button', { name: 'Allow once', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tab', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Enter', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Arrow keys' }).click();
  await expect(page.getByRole('button', { name: 'Up', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();

  await approvalCard.getByRole('button', { name: 'Allow once', exact: true }).click();
  await expect.poll(async () => (await operations()).some((operation) =>
    JSON.stringify(operation.argv) === JSON.stringify([
      'pane', 'send-keys', 'qoder-approval', 'Up', 'Up', 'Enter',
    ]))).toBe(true);

  await page.getByRole('button', { name: /Open qoder-notes on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: "Who's coming along, and how will you get there?",
  })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Type Something' })).toBeChecked();
  await expect(page.getByRole('textbox', { name: 'Type an answer' })).toHaveValue('I typed some notes here...');
  await expect(page.getByRole('button', { name: 'Arrow keys' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tab', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Enter', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();

  // These are captured screens, not running Codex TUIs: no PID/runtime proof is
  // supplied. Captured text must not grant approval or input authority. Positive
  // Codex question rendering/parsing is covered by component and Go parser tests.
  const unverified = ['codex-notes', 'codex-final-question', 'codex-single-question', 'codex-plan-approval'];
  for (const pane of unverified) {
    await page.getByRole('button', { name: `Open ${pane} on Captured relay`, exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Codex primary session is unverified' })).toBeVisible();
    await expect(page.getByRole('log', { name: 'Agent terminal output' })).not.toBeEmpty();
    await expect(page.locator('.question-form')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Keyboard', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Yes, implement this plan Switch to Default and start coding.', exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: 'Back' }).click();
  }
  expect((await operations()).filter(operation => unverified.includes(operation.argv?.[2] || '') && ['send-keys', 'send-text', 'send-input'].includes(operation.argv?.[1] || ''))).toEqual([]);
  expect(process.env.HERDR_ATTENTION_INPUT_OPERATIONS).toBeTruthy();
  expect((await operations(process.env.HERDR_ATTENTION_INPUT_OPERATIONS)).filter(operation => unverified.includes(operation.params?.pane_id || ''))).toEqual([]);

  await page.getByRole('button', { name: /Open omp-plan-approval on Captured relay/ }).click();
  await expect(page.getByRole('button', { name: 'Approve and execute', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve and compact context', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve and keep context (~18k / 272k)', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Refine plan', exact: true }).click();
  await expect.poll(async () => (await operations()).some((operation) =>
    JSON.stringify(operation.argv) === JSON.stringify([
      'pane', 'send-keys', 'omp-plan-approval', 'Down', 'Down', 'Down', 'Enter',
    ]))).toBe(true);
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open omp-partial-ask on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: 'What kind of weekend trip are you in the mood for?',
  })).toBeVisible();
  await expect(page.getByText('Question 1 of 4')).toBeVisible();
  await expect(page.getByRole('radio', { name: /Quiet lakeside cabin \(Recommended\)/ })).toBeChecked();
  await expect(page.getByRole('textbox')).toHaveCount(0);
  await page.getByRole('radio', { name: /Beach & coast/ }).check();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect.poll(async () => (await sentByThisRun())
    .filter((operation) =>
      operation.argv?.[1] === 'send-keys' &&
      operation.argv?.[2] === 'omp-partial-ask')
    .map((operation) => operation.argv?.slice(3))).toEqual([['Up'], ['Up'], ['Up'], ['Enter']]);
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open claude-approval on Captured relay/ }).click();
  await expect(page.getByRole('button', { name: 'Yes', exact: true })).toBeVisible();
  await expect(page.getByRole('button', {
    name: 'Yes, allow all edits during this session (shift+tab)',
    exact: true,
  })).toBeVisible();
  await expect(page.getByRole('button', { name: 'No', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open claude-later-question on Captured relay/ }).click();
  await expect(page.getByRole('group', { name: 'Do you need a database?' })).toBeVisible();
  await expect(page.getByText('Question 4 of 5')).toBeVisible();
  await expect(page.getByRole('radio', { name: /No database needed/ })).toBeChecked();
  await expect(page.getByRole('button', { name: '← Previous' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open claude-custom-answer on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: 'Which part of the Alps are you thinking of, or where are you starting from?',
  })).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Other', exact: true })).toBeChecked();
  await expect(page.getByRole('textbox', { name: 'Other answer' })).toHaveValue("I don't know");
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open claude-multi-select on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: 'Which activities would you like to include on your week-end trip?',
  })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Sightseeing/ })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: /Relaxation/ })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'Other', exact: true })).toBeChecked();
  await expect(page.getByRole('textbox', { name: 'Other answer' })).toHaveValue('Sport haha!!');
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open claude-review on Captured relay/ }).click();
  const claudeReview = page.getByRole('group', {
    name: 'Review your answers and choose what to do',
  });
  await expect(claudeReview).toBeVisible();
  await expect(page.getByText('Question 5 of 5')).toBeVisible();
  await expect(page.getByRole('radio', { name: /Submit answers/ })).toBeVisible();
  await expect(claudeReview.getByText(
    'What kind of webapp are you building: Content/marketing site',
  )).toBeVisible();
  await expect(page.getByRole('button', { name: '← Previous' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open opencode-single on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: 'What would you like to work on today?',
  })).toBeVisible();
  await expect(page.getByText('Question 1 of 1')).toBeVisible();
  await page.getByRole('radio', { name: /Write some code/ }).check();
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect.poll(async () => (await sentByThisRun())
    .filter((operation) =>
      operation.argv?.[1] === 'send-keys' &&
      operation.argv?.[2] === 'opencode-single')
    .map((operation) => operation.argv?.slice(3))).toEqual([['Up'], ['Up'], ['Enter']]);
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open opencode-custom on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: "What's your travel style and what are you looking for? (select all that apply)",
  })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Type your own answer' })).not.toBeChecked();
  await expect(page.getByRole('textbox', { name: 'Type your own answer' })).toHaveValue("That's great!!!");
  await expect(page.getByRole('button', { name: 'Tab', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open opencode-review on Captured relay/ }).click();
  const opencodeReview = page.getByRole('group', {
    name: 'Review your answers and choose what to do',
  });
  await expect(opencodeReview).toBeVisible();
  await expect(page.getByText('Question 5 of 5')).toBeVisible();
  // The terminal can show the same text; verify the interactive review summary.
  await expect(opencodeReview.getByText(/Trip vibe: Nature and outdoors/)).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: /Open qoder-standalone on Captured relay/ }).click();
  await expect(page.getByRole('group', {
    name: 'What is your preferred color?',
  })).toBeVisible();
  await expect(page.getByRole('radio', { name: /Green/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Submit', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Back' }).click();

  const settingsCard = page.locator('.agent-card').filter({ hasText: 'qoder-settings' });
  await expect(page.getByRole('heading', { name: 'Needs inspection' })).toBeVisible();
  await expect(settingsCard.getByRole('button', { name: 'Yes', exact: true })).toHaveCount(0);
  await settingsCard.getByRole('button', {
    name: /Open qoder-settings on Captured relay/,
  }).click();
  await expect(page.getByRole('combobox', { name: 'Prompt' })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Send prompt', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Keyboard', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tab', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Enter', exact: true })).toBeVisible();
  expect(await approvalsSent(['pane', 'send-keys', 'qoder-approval', 'Up', 'Up', 'Enter'])).toBe(1);
  expect(await approvalsSent(['pane', 'send-keys', 'omp-plan-approval', 'Down', 'Down', 'Down', 'Enter'])).toBe(1);
});

test('browses a full conversation through the real relay', async ({ page }) => {
  const wsURL = process.env.HERDR_ATTENTION_WS_URL;
  if (!wsURL) throw new Error('HERDR_ATTENTION_WS_URL is not configured');
  await page.addInitScript(({ relayURL }) => {
    localStorage.setItem('herdr_relays', JSON.stringify([{
      id: 'captured-attention',
      label: 'Captured relay',
      url: relayURL,
      token: 'attention-browser-test-key-32byt',
    }]));
    localStorage.setItem('herdr_default_agent_view', 'conversation');
  }, { relayURL: wsURL });
  await page.goto('/');

  await page.getByRole('region', { name: 'conversation-history tab', exact: true }).getByRole('button', { name: 'Open conversation on Captured relay' }).click();
  await expect(page.getByRole('heading', { name: 'Conversation', exact: true })).toBeVisible();
  await expect(page.getByText('history question')).toBeVisible();
  await expect(page.getByText('history answer')).toBeVisible();
  await page.getByRole('button', { name: 'Full history' }).click();
  await expect(page.getByRole('button', { name: 'Full history' })).toHaveAttribute('aria-pressed', 'true');
});
