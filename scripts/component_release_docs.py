"""Update published-version records and bounded README download sections."""
import json
import re

REPOSITORY = "cxnaive/herder_relay_extra"


def replace_block(text, key, body):
    start, end = f"<!-- {key}:start -->", f"<!-- {key}:end -->"
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError(f"Missing or duplicated {key} markers; refusing broad README replacement")
    before, rest = text.split(start, 1)
    _, after = rest.split(end, 1)
    return before + start + "\n" + body.rstrip() + "\n" + end + after


def android_block(metadata):
    release = metadata["android_release"]
    v, tag = release["version"], release["release_tag"]
    base = f"https://github.com/{REPOSITORY}/releases"
    rows = []
    for label, abi in (("ARM64 手机 / 平板", "arm64-v8a"), ("32 位 ARM", "armeabi-v7a"), ("x86_64 模拟器 / 设备", "x86_64")):
        rows.append(f"| {label} | [Release · {abi}]({base}/download/{tag}/Herdr-Pocket-{v}-release-{abi}.apk) |")
    return f'''## 下载 Android APK

**[{v} / versionCode {release['version_code']}]({base}/tag/{tag})**。

| 设备架构 | 下载 |
| --- | --- |
{chr(10).join(rows)}

附件还包含三种 ABI 的 Debug APK、`BUILD-INFO.json` 和 `SHA256SUMS.txt`。
所有 APK 直接复用 CI 固定签名产物，不重新编译或签名。**直接覆盖安装，不要卸载旧版**，以保留配对和草稿。
Android 9 / API 28 及以上；仍使用原有调试证书，不是 Play 商店版本。自行构建的签名可能不同。

发布来源：[CI run {release['release_build_run']}](https://github.com/{REPOSITORY}/actions/runs/{release['release_build_run']})，源码 `{release['release_source_commit']}`。
构建成功不等于完整 check 或真机验收全绿；验证范围和已知缺口见 Release 说明及 `herdr-android/VALIDATION.md`。
历史版本和更新内容保留在 Releases / CHANGELOG。'''


def native_block(metadata):
    release = metadata["native_release"]
    base = f"https://github.com/{REPOSITORY}/releases"
    rows = "\n".join(f"- [`{release[field]}`]({base}/download/{release['tag']}/{release[field]})"
                     for field in ("relay_artifact", "gateway_artifact", "checksums"))
    return f'''## 下载 Relay / Gateway 与部署指南

**[完整部署指南](docs/native-deployment.md)** · **[{release['tag']}]({base}/tag/{release['tag']})**

Linux x86_64 / amd64 完整部署包：

{rows}

包含二进制、Relay Web、配置/systemd 模板、初始化工具、部署说明及 BUILD-INFO。
其他架构的 `native-binaries-*` 仅是 CI 可执行文件，不是完整部署包。
公网服务器装 Gateway，运行 Herdr/Agent 的电脑装 Relay；已运行同版 Gateway 无需因 Relay 更新而重启。
升级保留现有配置、密钥和设备库，不重复初始化，不重启 Herdr/Agent；发布流程不部署服务器。

发布来源：[CI run {release['release_build_run']}](https://github.com/{REPOSITORY}/actions/runs/{release['release_build_run']})，源码 `{release['release_source_commit']}`。
Android 为 Latest；服务端版本和 Android 版本分别编号。'''


def update_docs(root, plan, receipt):
    if set(receipt["components"]) != set(plan["components"]):
        raise ValueError("Incomplete publication receipt")
    path = root / "VERSION.json"
    metadata = json.loads(path.read_text())
    previous = json.loads(path.read_text())
    for key, spec in plan["components"].items():
        record = receipt["components"][key]
        if record["tag"] != spec["tag"] or record["assets"] != spec["files"]:
            raise ValueError("Publication receipt does not match verified assets")
        if key == "android":
            a = plan["manifest"]["android"]
            metadata["android_release"] = {
                "version": a["version"], "version_code": a["version_code"], "release_tag": spec["tag"],
                "artifact": f"Herdr-Pocket-{a['version']}-release-arm64-v8a.apk",
                "release_source_commit": plan["source"], "release_build_run": plan["build_run_id"],
                "release_artifacts": spec["names"],
            }
        else:
            names = spec["names"]
            metadata["native_release"] = {
                "tag": spec["tag"], "targets": ["linux/amd64"],
                "relay_artifact": next(n for n in names if n.startswith("herdr-mobile-relay_")),
                "gateway_artifact": next(n for n in names if n.startswith("herdr-gateway_")),
                "checksums": "SHA256SUMS.txt", "release_source_commit": plan["source"],
                "release_build_run": plan["build_run_id"],
            }
    changes = {}
    readme = (root / "README.md").read_text()
    readme = replace_block(readme, "android-downloads", android_block(metadata))
    readme = replace_block(readme, "native-downloads", native_block(metadata))
    versions = {
        "电脑 Relay": f"`{metadata['relay']['version']}`",
        "原生 TCP Gateway": f"`{metadata['native_gateway']['version']}`",
        "Herdr Pocket Android": f"`{metadata['android']['version']}` / versionCode `{metadata['android']['version_code']}`",
    }
    for label, value in versions.items():
        readme = re.sub(r"(?m)^(\| " + re.escape(label) + r" \| )[^|]+(\|)", lambda m: m[1] + value + " " + m[2], readme)
    for binary, field in (("herdr-mobile-relay", "relay"), ("herdr-gateway", "native_gateway")):
        pattern = r"(-ldflags '-s -w -X main.version=)[^ ]+( -X main.revision=source-build' \\\n  -o bin/" + binary + ")"
        readme = re.sub(pattern, lambda m: m[1] + metadata[field]["version"] + m[2], readme)
    changes[root / "README.md"] = readme
    child = root / "herdr-android/README.md"
    changes[child] = replace_block(child.read_text(), "android-downloads", android_block(metadata).replace("## 下载 Android APK", "## 安装", 1))
    guide = root / "docs/native-deployment.md"
    text = guide.read_text()
    for field in ("tag", "relay_artifact", "gateway_artifact"):
        text = text.replace(previous["native_release"][field], metadata["native_release"][field])
    text = text.replace(previous["android_release"]["release_tag"], metadata["android_release"]["release_tag"])
    text = text.replace(previous["android_release"]["artifact"], metadata["android_release"]["artifact"])
    # Installed directory names are archive names without the .tar.gz suffix.
    for field in ("relay_artifact", "gateway_artifact"):
        text = text.replace(previous["native_release"][field].removesuffix(".tar.gz"), metadata["native_release"][field].removesuffix(".tar.gz"))
    changes[guide] = text
    changes[path] = json.dumps(metadata, indent=2, ensure_ascii=False) + "\n"
    # Validate all markers/records before changing any repository file.
    for target, text in changes.items():
        target.write_text(text)
