(() => {
  "use strict";
  const adminLinks = document.querySelectorAll("[data-admin-only]");
  if (!adminLinks.length || !/^https?:$/.test(location.protocol)) return;
  fetch("/staging/api/auth/me", {credentials:"same-origin",cache:"no-store"})
    .then(async response => response.ok ? response.json() : null)
    .then(result => {
      if (result?.user?.role === "admin") adminLinks.forEach(link => { link.hidden = false; });
    }).catch(() => {});
})();
