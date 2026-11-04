const e = new URL(window.__HERDR_ENTRY__ || "/builds/0.23.5-484-a1f5f94a1d6a96e1/index.html", location);
  e.search = location.search;
  e.hash = location.hash;
  location.replace(e);
