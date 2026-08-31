import { escapeHtml, renderPills } from "./assets/diagram-core.js";
import { functionalModules as data } from "./data/functional-modules.data.js";

const moduleById = new Map(data.modules.map((module) => [module.id, module]));

document.querySelector("#page-eyebrow").textContent = data.eyebrow;
document.querySelector("#page-title").textContent = data.title;
document.querySelector("#page-summary").textContent = data.summary;
document.querySelector("#page-stats").innerHTML = renderPills([
  `${data.entrances.length} 个产品入口`,
  `${data.entrances.flatMap((entrance) => entrance.domains).length} 个功能域`,
  `${data.modules.length} 个功能模块`,
  "完整层级同时可见",
]);

function moduleNode(moduleId) {
  const module = moduleById.get(moduleId);
  return `
    <article class="module-node" data-module-id="${escapeHtml(module.id)}" role="listitem">
      <h4>${escapeHtml(module.title)}</h4>
      <p>${escapeHtml(module.summary)}</p>
    </article>
  `;
}

function moduleBranch(count) {
  return `
    <div class="module-branch module-branch--${count}" aria-hidden="true">
      ${Array.from({ length: count }, () => "<span><i></i></span>").join("")}
    </div>
  `;
}

function domainRow(domain, index) {
  return `
    <section class="domain-row" role="listitem">
      <article class="domain-node">
        <span>${String(index + 1).padStart(2, "0")}</span>
        <h3>${escapeHtml(domain.title)}</h3>
      </article>
      ${moduleBranch(domain.moduleIds.length)}
      <div class="module-nodes module-nodes--${domain.moduleIds.length}" role="list" aria-label="${escapeHtml(domain.title)}内的并列功能模块">
        ${domain.moduleIds.map(moduleNode).join("")}
      </div>
    </section>
  `;
}

function entranceBranch(entrance, index) {
  return `
    <section class="entrance-branch entrance-branch--${escapeHtml(entrance.id)}" role="listitem">
      <article class="entrance-node">
        <span>产品入口 ${String(index + 1).padStart(2, "0")}</span>
        <h2>${escapeHtml(entrance.title)}</h2>
        <p>${escapeHtml(entrance.subtitle)}</p>
      </article>
      <div class="branch-bracket" aria-hidden="true">
        <span></span>
      </div>
      <div class="domain-rows" role="list" aria-label="${escapeHtml(entrance.title)}的功能域">
        ${entrance.domains.map(domainRow).join("")}
      </div>
    </section>
  `;
}

function mobileConnector() {
  return '<div class="functional-mobile-connector" aria-hidden="true"></div>';
}

function mobileDomainSection(domain, index) {
  return `
    <section class="functional-mobile-domain" role="listitem">
      <article class="domain-node">
        <span>${String(index + 1).padStart(2, "0")}</span>
        <h3>${escapeHtml(domain.title)}</h3>
      </article>
      ${mobileConnector()}
      <div class="functional-mobile-module-group" role="list" aria-label="${escapeHtml(domain.title)}内的并列功能模块">
        ${domain.moduleIds.map(moduleNode).join("")}
      </div>
    </section>
  `;
}

function mobileEntranceSection(entrance, index) {
  return `
    <section class="functional-mobile-entrance" role="listitem">
      <article class="entrance-node">
        <span>产品入口 ${String(index + 1).padStart(2, "0")}</span>
        <h2>${escapeHtml(entrance.title)}</h2>
        <p>${escapeHtml(entrance.subtitle)}</p>
      </article>
      ${mobileConnector()}
      <div class="functional-mobile-domains" role="list" aria-label="${escapeHtml(entrance.title)}的功能域">
        ${entrance.domains.map((domain, domainIndex) => `
          ${domainIndex > 0 ? mobileConnector() : ""}
          ${mobileDomainSection(domain, domainIndex)}
        `).join("")}
      </div>
    </section>
  `;
}

document.querySelector("#module-root").innerHTML = `
  <div class="diagram-reading-note">
    <span>读图顺序</span>
    <strong>素简记</strong><i>→</i><strong>产品入口</strong><i>→</i><strong>功能域</strong><i>→</i><strong>功能模块</strong>
  </div>

  <div class="diagram-vertical-flow" aria-label="向下阅读完整功能模块图">
    <p class="flow-cue">完整功能树 · 只需上下浏览</p>
    <section class="function-canvas" aria-label="PlainJournal 功能模块分解树">
      <div class="function-tree">
        <article class="function-root-node">
          <span>系统根节点</span>
          <h2>素简记</h2>
          <p>PlainJournal</p>
        </article>
        <div class="module-branch module-branch--2 root-branch" aria-hidden="true">
          <span><i></i></span>
          <span><i></i></span>
        </div>
        <div class="entrance-branches" role="list" aria-label="两个并列产品入口">
          ${data.entrances.map(entranceBranch).join("")}
        </div>
      </div>
    </section>

    <section class="function-mobile-canvas" aria-label="PlainJournal 功能模块分解树（移动端）">
      <div class="function-mobile-tree">
        <article class="function-root-node">
          <span>系统根节点</span>
          <h2>素简记</h2>
          <p>PlainJournal</p>
        </article>
        ${mobileConnector()}
        <div class="functional-mobile-entrances" role="list" aria-label="两个并列产品入口的功能分解">
          ${data.entrances.map(mobileEntranceSection).join("")}
        </div>
      </div>
    </section>
  </div>

  <section class="ownership-index" aria-labelledby="ownership-title">
    <header>
      <p class="diagram-eyebrow">图后索引</p>
      <h2 id="ownership-title">功能归产品 · 事实归所有者</h2>
      <p>主图不混入技术结构；需要追溯时，再从这里找到主责服务。</p>
    </header>
    <div class="ownership-table" role="table" aria-label="功能模块与主责服务映射">
      ${data.modules.map((module) => `
        <div class="ownership-row" role="row">
          <span role="cell">${escapeHtml(module.title)}</span>
          <strong role="cell">${escapeHtml(module.owner)}</strong>
        </div>
      `).join("")}
    </div>
  </section>
`;

document.querySelector("#boundary-root").innerHTML = `
  <p class="diagram-eyebrow">组合边界</p>
  <ul class="principle-list">${data.boundaries.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>
`;
