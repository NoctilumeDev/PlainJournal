import { escapeHtml, renderPills } from "./assets/diagram-core.js";
import { systemArchitecture as data } from "./data/system-architecture.data.js";

const services = data.serviceGroups.flatMap((group) => group.services);

document.querySelector("#page-eyebrow").textContent = data.eyebrow;
document.querySelector("#page-title").textContent = data.title;
document.querySelector("#page-summary").textContent = data.summary;
document.querySelector("#page-stats").innerHTML = renderPills([
  "2 个产品入口",
  "1 个统一网关",
  `${services.length} 个事实所有者`,
  "完整层级同时可见",
]);

function branch(count, extraClass = "") {
  return `
    <div class="architecture-branch architecture-branch--${count} ${extraClass}" aria-hidden="true">
      ${Array.from({ length: count }, () => "<span><i></i></span>").join("")}
    </div>
  `;
}

function entranceNode(actor, experience, index) {
  return `
    <article class="architecture-entrance-node" role="listitem">
      <span>产品入口 ${String(index + 1).padStart(2, "0")}</span>
      <h2>${escapeHtml(experience.title)}</h2>
      <p>${escapeHtml(experience.subtitle)}</p>
      <small>${escapeHtml(actor.title)} · ${escapeHtml(actor.detail)}</small>
    </article>
  `;
}

function serviceNode(service) {
  return `
    <article class="architecture-service-node" data-service-id="${escapeHtml(service.id)}" role="listitem">
      <h4>${escapeHtml(service.title)}</h4>
      <p>${escapeHtml(service.detail)}</p>
      <strong>${escapeHtml(service.id)}</strong>
      <small>MySQL · ${escapeHtml(service.owner)}</small>
    </article>
  `;
}

function mobileConnector() {
  return '<div class="architecture-mobile-connector" aria-hidden="true"></div>';
}

function mobileEntranceNode(actor, experience, index) {
  return `
    <article class="architecture-mobile-card architecture-mobile-entrance-card" role="listitem">
      <span>产品入口 ${String(index + 1).padStart(2, "0")}</span>
      <h2>${escapeHtml(experience.title)}</h2>
      <p>${escapeHtml(experience.subtitle)}</p>
      <small>${escapeHtml(actor.title)} · ${escapeHtml(actor.detail)}</small>
    </article>
  `;
}

function mobileServiceNode(service) {
  return `
    <article class="architecture-mobile-card architecture-mobile-service-card" data-service-id="${escapeHtml(service.id)}" role="listitem">
      <h4>${escapeHtml(service.title)}</h4>
      <p>${escapeHtml(service.detail)}</p>
      <strong>${escapeHtml(service.id)}</strong>
      <small>MySQL · ${escapeHtml(service.owner)}</small>
    </article>
  `;
}

function mobileOwnerGroup(group, index) {
  return `
    <section class="architecture-mobile-owner-group" role="listitem">
      <header class="architecture-mobile-domain-heading">
        <span>${String(index + 1).padStart(2, "0")}</span>
        <h3>${escapeHtml(group.label)}</h3>
      </header>
      <div class="architecture-mobile-service-tier" role="list" aria-label="${escapeHtml(group.label)}内的并列事实所有者">
        ${group.services.map(mobileServiceNode).join("")}
      </div>
    </section>
  `;
}

function ownerBranch(group, index) {
  return `
    <section class="architecture-owner-branch" role="listitem">
      <article class="architecture-domain-node">
        <span>${String(index + 1).padStart(2, "0")}</span>
        <h3>${escapeHtml(group.label)}</h3>
      </article>
      <div class="architecture-service-list" role="list" aria-label="${escapeHtml(group.label)}内的并列事实所有者">
        ${group.services.map(serviceNode).join("")}
      </div>
    </section>
  `;
}

const synchronousSummary = data.synchronous
  .map((call) => `${call.from} → ${call.to}：${call.label}`)
  .join(" · ");

const eventSummary = `${data.eventFlow.producers.join(" / ")} → ${data.eventFlow.broker} → ${data.eventFlow.consumers.join(" / ")}`;

document.querySelector("#architecture-root").innerHTML = `
  <div class="diagram-reading-note">
    <span>读图顺序</span>
    <strong>素简记</strong><i>→</i><strong>并列产品入口</strong><i>→</i><strong>统一网关</strong><i>→</i><strong>并列事实所有者</strong>
  </div>

  <div class="diagram-vertical-flow" aria-label="向下阅读完整系统架构图">
    <p class="flow-cue">完整架构树 · 只需上下浏览</p>
    <section class="architecture-tree-canvas" aria-label="PlainJournal 系统架构所有权树">
      <div class="architecture-tree">
        <article class="architecture-root-node">
          <span>系统根节点</span>
          <h2>素简记</h2>
          <p>PlainJournal</p>
        </article>

        ${branch(data.experiences.length, "architecture-root-branch")}

        <div class="architecture-entrances" role="list" aria-label="两个并列产品入口">
          ${data.actors.map((actor, index) => entranceNode(actor, data.experiences[index], index)).join("")}
        </div>

        <div class="architecture-merge" aria-hidden="true"><span></span><span></span><i></i></div>

        <article class="architecture-gateway-node">
          <span>${escapeHtml(data.gateway.subtitle)}</span>
          <h2>${escapeHtml(data.gateway.title)}</h2>
          <p>${escapeHtml(data.gateway.detail)}</p>
          <small>统一访问边界 · 不拥有领域事实</small>
        </article>

        ${branch(data.serviceGroups.length, "architecture-owner-split")}

        <div class="architecture-owner-branches" role="list" aria-label="四个并列所有权域">
          ${data.serviceGroups.map(ownerBranch).join("")}
        </div>
      </div>
    </section>

    <section class="architecture-mobile-canvas" aria-label="PlainJournal 系统架构所有权树（移动端）">
      <div class="architecture-mobile-tree">
        <article class="architecture-mobile-card architecture-mobile-root-card">
          <span>系统根节点</span>
          <h2>素简记</h2>
          <p>PlainJournal</p>
        </article>

        ${mobileConnector()}

        <div class="architecture-mobile-entrance-tier" role="list" aria-label="两个并列产品入口">
          ${data.actors.map((actor, index) => mobileEntranceNode(actor, data.experiences[index], index)).join("")}
        </div>

        ${mobileConnector()}

        <article class="architecture-mobile-card architecture-mobile-gateway-card">
          <span>${escapeHtml(data.gateway.subtitle)}</span>
          <h2>${escapeHtml(data.gateway.title)}</h2>
          <p>${escapeHtml(data.gateway.detail)}</p>
          <small>统一访问边界 · 不拥有领域事实</small>
        </article>

        ${mobileConnector()}

        <div class="architecture-mobile-owner-tier" role="list" aria-label="四个并列所有权域">
          ${data.serviceGroups.map(mobileOwnerGroup).join("")}
        </div>
      </div>
    </section>
  </div>

  <section class="architecture-index" aria-labelledby="architecture-index-title">
    <header>
      <p class="diagram-eyebrow">图后索引</p>
      <h2 id="architecture-index-title">协作有路径 · 事实有归处</h2>
      <p>主图只保留访问与所有权；同步裁决、事件收敛和运行底座在这里展开。</p>
    </header>

    <div class="architecture-collaboration-index">
      <article class="architecture-index-card architecture-index-row">
        <span>同步短链</span>
        <strong>${escapeHtml(synchronousSummary)}</strong>
      </article>
      <article class="architecture-index-card architecture-index-row">
        <span>异步事件</span>
        <strong>${escapeHtml(eventSummary)}</strong>
      </article>
    </div>

    <p class="diagram-eyebrow architecture-foundation-label">运行底座</p>
    <div class="architecture-foundation-grid" role="table" aria-label="系统运行底座">
      ${data.infrastructure.map((item) => `
        <div class="architecture-index-card" role="row">
          <span role="cell">${escapeHtml(item.title)}</span>
          <strong role="cell">${escapeHtml(item.detail)}</strong>
        </div>
      `).join("")}
    </div>
  </section>
`;

document.querySelector("#principle-root").innerHTML = `
  <p class="diagram-eyebrow">架构边界</p>
  <ul class="architecture-principle-list">${data.principles.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>
`;
