const e = new URL(window.__HERDR_ENTRY__ || "/builds/0.23.5-486-b6acb78c2bec5f2b/index.html", location);
  e.search = location.search;
  e.hash = location.hash;
  location.replace(e);
