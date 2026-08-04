(function () {
  "use strict";

  var body = document.body;
  var sidebar = document.getElementById("sidebar");
  var menuToggle = document.getElementById("menu-toggle");
  var sidebarClose = document.getElementById("sidebar-close");
  var searchInput = document.getElementById("doc-search");
  var searchStatus = document.getElementById("search-status");
  var themeToggle = document.getElementById("theme-toggle");
  var navLinks = Array.prototype.slice.call(document.querySelectorAll("[data-nav]"));
  var sections = Array.prototype.slice.call(document.querySelectorAll(".doc-section[id]"));

  function setNavOpen(open) {
    body.classList.toggle("nav-open", open);
    if (menuToggle) menuToggle.setAttribute("aria-expanded", String(open));
  }

  if (menuToggle) menuToggle.addEventListener("click", function () { setNavOpen(true); });
  if (sidebarClose) sidebarClose.addEventListener("click", function () { setNavOpen(false); });

  navLinks.forEach(function (link) {
    link.addEventListener("click", function () { setNavOpen(false); });
  });

  document.addEventListener("click", function (event) {
    if (!body.classList.contains("nav-open") || !sidebar) return;
    if (!sidebar.contains(event.target) && !menuToggle.contains(event.target)) setNavOpen(false);
  });

  function setTheme(theme) {
    if (theme === "dark") {
      document.documentElement.setAttribute("data-theme", "dark");
      if (themeToggle) themeToggle.setAttribute("aria-pressed", "true");
    } else {
      document.documentElement.removeAttribute("data-theme");
      if (themeToggle) themeToggle.setAttribute("aria-pressed", "false");
    }
  }

  var savedTheme = null;
  try { savedTheme = window.localStorage.getItem("tengen-docs-theme"); } catch (ignore) { /* no-op */ }
  setTheme(savedTheme === "dark" ? "dark" : "light");

  if (themeToggle) {
    themeToggle.addEventListener("click", function () {
      var isDark = document.documentElement.getAttribute("data-theme") === "dark";
      var next = isDark ? "light" : "dark";
      setTheme(next);
      try { window.localStorage.setItem("tengen-docs-theme", next); } catch (ignore) { /* no-op */ }
    });
  }

  function addCopyButtons() {
    document.querySelectorAll(".code-wrap").forEach(function (wrap) {
      var code = wrap.querySelector("pre code");
      if (!code || wrap.querySelector(".copy-button")) return;
      var button = document.createElement("button");
      button.type = "button";
      button.className = "copy-button";
      button.textContent = "Copy";
      button.setAttribute("aria-label", "Copy code example");
      button.addEventListener("click", function () {
        var text = code.textContent;
        var done = function () {
          button.textContent = "Copied";
          button.classList.add("copied");
          window.setTimeout(function () {
            button.textContent = "Copy";
            button.classList.remove("copied");
          }, 1400);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done).catch(function () { fallbackCopy(text, done); });
        } else {
          fallbackCopy(text, done);
        }
      });
      wrap.appendChild(button);
    });
  }

  function fallbackCopy(text, done) {
    var area = document.createElement("textarea");
    area.value = text;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.focus();
    area.select();
    try { document.execCommand("copy"); } catch (ignore) { /* no-op */ }
    document.body.removeChild(area);
    done();
  }

  addCopyButtons();

  function updateActiveLink(id) {
    navLinks.forEach(function (link) {
      var active = link.getAttribute("href") === "#" + id;
      link.classList.toggle("active", active);
      if (active) link.setAttribute("aria-current", "location");
      else link.removeAttribute("aria-current");
    });
  }

  if ("IntersectionObserver" in window) {
    var observer = new IntersectionObserver(function (entries) {
      var visible = entries
        .filter(function (entry) { return entry.isIntersecting; })
        .sort(function (a, b) { return b.intersectionRatio - a.intersectionRatio; });
      if (visible[0]) updateActiveLink(visible[0].target.id);
    }, { rootMargin: "-92px 0px -58% 0px", threshold: [0.05, 0.2, 0.5] });
    sections.forEach(function (section) { observer.observe(section); });
  }

  function filterDocs(query) {
    var normalized = query.trim().toLowerCase();
    var visibleCount = 0;
    sections.forEach(function (section) {
      var haystack = (section.getAttribute("data-title") || "") + " " + section.textContent;
      var visible = !normalized || haystack.toLowerCase().indexOf(normalized) !== -1;
      section.classList.toggle("search-hidden", !visible);
      if (visible) visibleCount += 1;
    });

    navLinks.forEach(function (link) {
      var id = link.getAttribute("href").slice(1);
      var target = document.getElementById(id);
      var visible = target && !target.classList.contains("search-hidden");
      link.classList.toggle("search-hidden", !visible);
    });

    document.querySelectorAll(".nav-group").forEach(function (group) {
      var visibleLinks = group.querySelectorAll("a[data-nav]:not(.search-hidden)").length;
      group.classList.toggle("nav-hidden", visibleLinks === 0);
    });

    if (searchStatus) {
      searchStatus.textContent = normalized ? visibleCount + " matching sections" : "";
    }
  }

  if (searchInput) {
    searchInput.addEventListener("input", function (event) { filterDocs(event.target.value); });
  }

  document.addEventListener("keydown", function (event) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      if (searchInput) {
        searchInput.focus();
        searchInput.select();
      }
    }
    if (event.key === "Escape" && body.classList.contains("nav-open")) setNavOpen(false);
  });
})();
