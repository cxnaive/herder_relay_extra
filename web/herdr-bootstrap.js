const e = new URL(window.__HERDR_ENTRY__ || "/builds/0.23.5-489-6dec9e9e504fb4a4/index.html", location);
  e.search = location.search;
  e.hash = location.hash;
  location.replace(e);
