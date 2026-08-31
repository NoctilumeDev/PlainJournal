class PlainJournalPaperAtmosphere extends HTMLElement {
  connectedCallback() {
    if (this.dataset.mounted === "true") {
      return;
    }
    this.dataset.mounted = "true";
    this.setAttribute("aria-hidden", "true");
    this.innerHTML = `
      <span class="paper-fold paper-fold--1"></span>
      <span class="paper-fold paper-fold--2"></span>
      <span class="paper-fold paper-fold--3"></span>
      <span class="paper-fold paper-fold--4"></span>
      <span class="paper-fold paper-fold--5"></span>
      <span class="paper-fold paper-fold--6"></span>
      <span class="paper-wash paper-wash--west"></span>
      <span class="paper-wash-light paper-wash-light--west"></span>
      <span class="paper-wash paper-wash--east"></span>
      <span class="paper-wash paper-wash--south"></span>
      <span class="paper-wash paper-wash--drop"></span>
      <span class="paper-leaf"></span>
    `;

    if (document.body.classList.contains("functional-page") && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      this.onScroll = () => {
        if (this.scrollFrame) {
          return;
        }
        this.scrollFrame = window.requestAnimationFrame(() => {
          this.scrollFrame = 0;
          const drift = Math.min(72, window.scrollY * 0.028);
          this.style.setProperty("--paper-drift", `${-drift}px`);
        });
      };
      window.addEventListener("scroll", this.onScroll, { passive: true });
      this.onScroll();
    }
  }

  disconnectedCallback() {
    if (this.onScroll) {
      window.removeEventListener("scroll", this.onScroll);
    }
    if (this.scrollFrame) {
      window.cancelAnimationFrame(this.scrollFrame);
    }
  }
}

if (!customElements.get("pj-paper-atmosphere")) {
  customElements.define("pj-paper-atmosphere", PlainJournalPaperAtmosphere);
}
