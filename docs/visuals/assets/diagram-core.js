export function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

export function renderPills(items, className = "meta-pill") {
  return items
    .map((item) => `<span class="${className}">${escapeHtml(item)}</span>`)
    .join("");
}
