# PlainJournal Visual Docs Design QA

## Source visual truth

The visual target combines two kinds of evidence rather than copying one template:

- Structural conventions for the system topology:
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/mall-swarm-arch.jpg`
    (`2421 x 1776` px)
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/dotnet-eshop.png`
    (`2940 x 1648` px)
- Structural conventions for the functional decomposition:
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/mall-re_mall_business_arch.jpg`
    (`1765 x 882` px)
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/mall-mind_portal.jpg`
    (`788 x 4371` px)
- PlainJournal's approved visual shell: restrained paper texture, ink-green type, pale washes and
  vermillion accents from the existing `docs/visuals/assets/paper.css` study.
- Information source of truth:
  - `docs/visuals/system-architecture-draft.md`
  - `docs/visuals/functional-modules-draft.md`
  - `docs/02-service-architecture.md`
  - `docs/04-data-ownership.md`
  - real service directories and Storefront/Admin task boundaries.

The source diagrams are structural references, not pixel-clone targets. The implementation must
inherit their immediately readable boxes, trunks, branches, layers and arrow direction while
remaining visually native to PlainJournal.

## Implementation evidence

- Desktop public captures:
  - `docs/assets/visuals/system-architecture.png` (`1440 x 1327` px)
  - `docs/assets/visuals/functional-modules.png` (`1425 x 1306` px, vertical-tree iteration)
- Mobile captures at a `390 x 844` CSS viewport:
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/plainjournal-system-mobile-final.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/plainjournal-functional-vertical-mobile.png`
- Same-input comparison boards:
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/system-comparison-final.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-comparison-final.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-vertical-comparison.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-narrow-unified-comparison.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-cleanup-comparison.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-connector-gradient-comparison.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-paired-compact-comparison.png`
  - `C:/Users/lenovo/AppData/Local/Temp/plainjournal-diagram-research/functional-spacing-centered-comparison.png`

## Comparison findings

### System architecture

- Matches the reference convention of a single top-to-bottom request path: actors and clients,
  gateway, service owners, collaboration lanes, then infrastructure.
- The architecture page now follows the approved functional-diagram paradigm: one centered
  narrative axis, a `64rem` vertical tree canvas, visible root and hierarchy, a separate post-diagram
  index and a three-item boundary section. The content remains architecture-specific.
- The main tree shows two product entrances merging into one gateway, then fans out to four ownership
  domains and all ten fact-owning services. The previous `74rem` horizontal topology and mobile
  sideways-scrolling requirement are removed.
- Synchronous calls, RocketMQ convergence and the eight runtime dependencies move into the post-tree
  index. This preserves their evidence without turning the ownership tree into a connector web.
- Architecture cards use the approved low-saturation celadon gradient sampled from the product-study
  cup. The cool grey-green transitions back to warm paper at `61.8%`; the canvas and grouping frames
  remain neutral so the page does not become uniformly green.
- The architecture tree now renders directly on the approved paper plate. Its former translucent
  white canvas, the four empty owner-group frames and the boxed flow cue were removed because they
  carried no architectural meaning. Only fact-bearing node cards and relation lines remain.
- Root-to-entrance, entrance-to-gateway and gateway-to-owner connector tiers now share the same
  `1.4rem` half-gap. The architecture root previously inherited an unresolved functional-page
  variable and collapsed to zero height; it now measures `44.79px`, matching the merge and owner
  fan-out tiers at the test scale.
- After the frame removal, owner fan-out endpoints terminate directly at the domain-card edge rather
  than at a decorative container boundary. Service rails begin at the domain-card edge and end at
  the final sibling card centre.
- The four owner groups and ten service nodes remain visible simultaneously. No filter, carousel,
  auto-advance or faded branch is required to understand the topology.
- Straight connectors and compact labels carry the topology; the paper atmosphere remains behind
  the information and does not compete with it.
- Synchronous Trade calls and asynchronous RocketMQ convergence are visibly separated instead of
  being described only in prose.

### System architecture background plate

- The approved source plate is
  `C:/Users/lenovo/AppData/Local/Temp/codex-clipboard-5a4af13d-8bea-4547-add7-7065ddd35c86.png`:
  a text-free xuan-paper field with diagonal post-rain light, sparse water drops, a cropped lotus
  leaf at bottom left and restrained grey-green haze.
- The repository asset is `docs/visuals/assets/system-architecture-paper.png`. It is applied only
  under `.architecture-page`; the functional module page keeps its existing CSS-built folds,
  washes and leaf.
- Desktop placement uses a centered `cover` crop. At `390 x 844`, the crop is anchored left and
  bottom so the lotus remains legible without enlarging into the reading column. Both layouts keep
  the background fixed behind the architecture content and introduce no document-width overflow.
- The legacy atmosphere children are hidden only within the architecture page, preventing the old
  right-side CSS leaf and fold lines from competing with the approved raster composition.
- Same-input visual comparison:
  `C:/Users/lenovo/AppData/Local/Temp/plainjournal-design-audit/03-system-architecture-paper-comparison.png`.
  The comparison confirms that the paper grain, diagonal light, droplets, lotus crop and haze retain
  their source proportions while the diagram remains readable above them.

### Functional modules

- Matches the reference convention of one root, two product entrances, six task domains and sixteen
  leaf modules.
- The second iteration changes the main tree from a wide left-to-right map into a top-to-bottom
  hierarchy. The root, customer entrance, customer domains, admin entrance and admin domains now
  form one continuous vertical reading path.
- Customer and admin remain distinct through their `01 / 02` entrance numbering and separate
  full-width sections, without introducing a different page or interaction state.
- The third iteration narrows the functional content column from the shared `96rem` canvas to a
  centered `64rem` maximum. At `1280px` the main content is `1024px` wide instead of filling the
  `1216px` shared diagram width.
- Root, entrance, domain and module nodes now share exactly one paper fill, one border color and one
  square-corner rule. Product entrances are distinguished by numbering and hierarchy rather than
  conflicting green/red card treatments.
- The fourth iteration removes the repeated English canvas label and the decorative left-side
  domain rail. The page title, reading-order strip and entrance frame already carry those meanings,
  so removing them reduces visual machinery without losing hierarchy.
- Domain-to-module relations now use a real tree branch on desktop: one vertical stem splits into
  two or three arrowed child paths aligned with the target cards. At the mobile breakpoint the
  branch collapses to one vertical arrow above the stacked module group.
- The connector implementation now derives every child spoke from the same CSS grid as its target
  card. Measured spoke-to-card center differences stay below `0.01px`; the previous independent
  percentage positioning and visible one-pixel seam are gone.
- A centered continuation spine carries the reading path between adjacent domain groups. It remains
  visible through the gap in a two-card row and passes behind the opaque middle card in a three-card
  row, so no line appears disconnected or doubled.
- Customer and administration entrances now branch directly from the root into two desktop columns.
  Their `479.74px` grouping frames share one row, while root and entrance cards are capped at
  `14rem`, domain cards at `16rem`, and leaf cards use only their grid column width.
- The hierarchy now has one restrained extra step of breathing room. Desktop domain groups use a
  `26.4px` vertical gap and `34.4px` branch height; parallel leaf cards use a `10.4px` gutter and the
  two entrance frames use an `18.4px` gutter. Every branch reads the same gutter variable as its
  target grid, so the added space does not introduce connector drift.
- The functional page now uses a single centered narrative axis: eyebrow, title, summary, summary
  statistics, reading order, flow cue, root, entrance, domain, leaf, ownership heading and ownership
  rows all align to the center. Navigation and footer chrome retain their page-level alignment.
- Title-to-summary, summary-to-statistics, statistics-to-reading-order and reading-order-to-flow-cue
  spacing now resolve to the same `16px` rhythm. The previous full-width borders around the reading
  order were replaced by a restrained `30rem` rule aligned to one inner entrance frame instead of
  the outer canvas. The measured desktop difference is `0.26px`; the mobile difference remains
  below `0.32px` after the tree inset is applied.
- The ownership heading remains one line at desktop and mobile widths. Its sixteen ownership cards
  now use individual borders with an `8.8px` gutter instead of touching through a one-pixel grid,
  preserving index density without the previous nine-grid effect.
- The ownership separator retains the outer `64rem` content measure: ownership section and function
  canvas both measure `1024px`. A second restrained rule now closes the “图后索引” label before the
  title begins; both rules stop `32px` inside the outer measure and therefore align to the ownership
  cards' inner reading axis. The label sits `11.2px` from both its upper and lower rule.
- The ownership and boundary dividers now stop `32px` inside that retained outer measure, while the
  ownership cards keep their full `1024px` alignment. The three boundary statements use the same
  inset, so their top rules share one visible start and end axis. On mobile the inset contracts to
  `12.8px` rather than compressing the content.
- The boundary section now begins `24px` after the ownership table; the “组合边界” label sits
  `11.2px` from both the section divider and the three statement rules. Functional-page bottom
  padding is `40px`, its footer margin is `12px`, and footer text begins `13.46px` below the rule;
  the complete provenance row is visibly raised without altering the system diagram footer.
- The ownership title uses a centered interpunct (`功能归产品 · 事实归所有者`) instead of a baseline
  Chinese comma. It remains a single line at both desktop and `390px` mobile widths.
- Card fill is sampled from the reference wash and uses one shared pale sage-to-paper gradient. The
  primary color transition reaches warm paper at the `61.8%` stop; the final `38.2%` is a restrained
  tail instead of an imperceptible full-width fade.
- On the functional page only, the paper atmosphere now drifts at `2.8%` of scroll distance with a
  `72px` cap. The movement is disabled when reduced motion is requested; the system architecture
  page retains its previous fixed atmosphere.
- The main tree answers only “what the product does”. Service ownership is intentionally moved to a
  separate index so technical provenance does not obscure the functional hierarchy.
- Every domain and leaf remains visible in the base state; no content disappears during interaction.
- On mobile, a stacked two- or three-card module group is still expressed as siblings: the parent
  connector turns into a left-side rail and each card receives its own right-pointing arrow. The
  continuation to the next numbered domain starts only below the completed group, so parallel
  modules no longer read as a false sequential workflow.
- Connector paths and arrowheads now use the same border-based stroke rather than mixing one-pixel
  backgrounds with rotated one-pixel borders. At the test browser's `1.5` device scale, horizontal,
  vertical, continuation and arrow-tip strokes all resolve to `0.666667px`; center error remains
  `0px` and no doubled overlap is visible.
- Arrowheads now terminate at the boundary of the node they point to: root to product entrance,
  product entrance or preceding module group to numbered domain, and domain to parallel modules.
  The former arrowheads on entrance-frame borders were removed because they described container
  geometry rather than information flow.
- Incoming stems now run to the same card-edge anchor as their arrowheads. Public-preview endpoint
  measurements are effectively zero: root-to-frame `0.00004px`, frame-to-entrance `-0.00007px`,
  bracket-to-domain `-0.0083px`, branch-to-module `0.00002px` and continuation-to-domain
  `-0.0042px`. Source-side arrowheads were removed, eliminating the previous `0.67px` lateral
  drift and the visible `2.88px` / `4.8px` connector gaps.
- Branch bars now bisect their complete visible connector gaps instead of only their own helper
  elements. Ordinary domain branches measure `15.60px` above and `15.60px` below the bar. At the
  root, canvas-to-card, card-to-split and split-to-entrance-frame spacing all resolve to the shared
  `--root-tier-gap` (`22.4px`, apart from the canvas border subpixel). This removes the unequal
  spacing visible at both two-way and three-way fan-outs.

## Responsive and interaction verification

- Desktop browser state: `1280 x 720`, `1440 x 1000` and `1440 x 1320` CSS viewports.
- Mobile browser state: `390 x 844` CSS viewport.
- Desktop document width stayed within the viewport; all ten service nodes and all sixteen module
  nodes rendered without text clipping.
- Mobile document width stayed within the viewport (`375` px layout width).
- The rebuilt architecture tree measures `350.67px` at the `390 x 844` viewport and the document
  remains `375px` wide. All four owner groups and ten service cards are available by vertical
  scrolling; the main diagram no longer creates a horizontal scroll region.
- The system architecture keeps four equal desktop owner columns but collapses them into one bounded
  column on mobile; neither state creates a horizontal document overflow. The functional module page
  also has no horizontal scroll wrapper: its flow and canvas remain within `351` px and `349` px
  respectively at the `390 x 844` viewport.
- All sixteen functional modules remain readable by vertical page scrolling; no module or connector
  crosses the mobile viewport edge.
- The functional canvas contains no repeated internal title strip and no decorative domain rail;
  the two entrance sections, six domains and sixteen modules remain present after the cleanup.
- At `1440px`, the two entrance columns measure `479.74px` each; two-leaf cards measure about
  `219.6px` and three-leaf cards about `142.94px`, with no clipped labels or summaries.
- At `1440px`, module branch spokes and their target card centers differ by `0px`; the centered
  ownership title renders on one line and all sixteen index cards remain inside the document width.
- At `390px`, the two entrances return to one `323.75px` column, the root split collapses to a single
  vertical connector, the functional canvas stays at `350.67px`, and document width remains `375px`.
- Both diagram links remain visible and clickable on mobile; the external source link is the only
  link hidden at the narrowest breakpoint.
- Desktop and mobile page-to-page navigation was exercised in both directions.
- The generated public preview was checked at desktop and mobile widths: both card images loaded at
  their natural `1440 x 1327` size, both cards navigated to the correct pages, and the mobile card
  grid collapsed to one column without document-level overflow.
- Browser console verification returned zero warnings and zero errors on the final desktop and
  mobile reloads.

## Repository alignment and automated checks

- Rendered applications: `11` (`1` gateway plus `10` owner services).
- Owner schemas: `10`, all unique.
- Functional modules: `16`.
- Manual route/workspace reconciliation found no semantic drift: the customer tree is evidenced by
  identity, catalog/search, bag/checkout, order, after-sale, benefit, notification and support
  routes; the administration tree is evidenced by catalog/review, marketing, inventory,
  fulfillment/after-sale, chat and governance workspaces.
- Functional presentation rules remain isolated either by the `.functional-page` root or by
  function-tree-only class names. No architecture renderer, architecture data, or architecture
  selector was changed during the final spacing pass.
- The new raster plate and celadon node treatment are scoped under `.architecture-page`. Browser
  regression checks confirm the functional page still has no raster background, retains twelve
  visible CSS-atmosphere layers and renders its two entrances and sixteen modules unchanged.
- `docs/assets/visuals/functional-modules.png` was recaptured from the generated public page at
  `1440 x 1313`, so the README/public-preview card matches the final equal-spacing implementation.
- `node tools/check-visual-docs.mjs` passed.
- `node --test tools/check-visual-docs.test.mjs` passed: `5 / 5`.
- JavaScript syntax checks passed for both renderers and both data files.
- Architecture connector strokes and arrowhead borders all resolve to the same `0.666667px` at the
  test browser's device scale: root stem, split bars, merge stems, the gateway-to-owner fan-out and
  domain arrows. Services inside one ownership domain are siblings, so their former sequential
  arrowheads were removed. A short stem now joins each domain to one restrained containment frame;
  service cards inside that frame use equal `14.40px` gaps.
- Architecture-only arrowheads were reduced from `8px` to `6px`. Their `-7.24px` offset keeps the
  downward tip on the target border while reducing the visual projection above the card. The four
  containment stems share the exact center axis of their domain cards (`0px` measured offset).
- The four-way desktop owner fan-out now includes the previously missing gateway-centre stem. Its
  branch top begins exactly at the gateway bottom and all four branch bottoms coincide with their
  domain-card tops. On mobile, a final architecture-scoped rule follows the generic tree fallback,
  disabling the fallback's stray left-to-centre pseudo-line while retaining the centred down stem.
- Mobile no longer converts parallel entrances, owner domains or services into a false sequential
  arrow chain. Entrances and owner domains each collapse into one light containment frame; domain
  services use their own nested containment frame. Only the root/gateway access spine retains
  direction, and all peer-node arrow pseudo-elements resolve to `none` at `390px`.
- `git diff --check` passed.

## Severity review

- P0: none.
- P1: none.
- P2: none.
- P3: the desktop README captures are previews rather than substitutes for the interactive pages;
  the HTML pages retain the complete topology and full functional tree.

## Final result

passed

---

# 2026-09-01 Admin Foundation Design QA

## Source visual truth

- Selected management baseline:
  `docs/assets/frontend-design/2026-09-01-admin-foundation/selected-split-workbench.png`
  (`1487 x 1058` px).
- The source is treated as the approved information architecture: a stable top workspace shell,
  task filters, a selectable object queue, and one fact-and-action detail surface.
- PlainJournal's existing paper, ink, low-saturation green, typography, spacing and status tokens
  remain the visual system. The implementation does not import a second template library or copy
  the reference's data model.

## Implementation evidence

- Refined desktop capture:
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/03-after-sales-foundation-desktop-refined.png`.
- Mobile capture at a `390 x 844` CSS viewport:
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/04-after-sales-foundation-mobile.png`.
- Same-input visual comparison:
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/06-source-implementation-comparison.png`.
- Working route: `http://127.0.0.1:18201/after-sales`.

## Comparison findings

- The approved left-to-right task grammar is present at desktop: filter rail, queue and detail
  measure `208px`, `435.20px` and `621.47px` at the default browser viewport.
- Dividers belong to the workbench layout rather than to nested surfaces. The migrated page contains
  no `PjSurface` stack and the management shell contains no legacy left navigation.
- The top shell now carries brand, workspace switching, current operator and logout. It remains
  independent from the customer shell and does not introduce page-level global overrides.
- The queue scans real Trade projections and keeps the current selection visible. The detail pane
  shows reference number, order, amount, status, reason, timestamps, current owner, next step and
  immutable line snapshot before exposing an eligible command.
- The real browser fixture currently contains one `WAIT_RETURN` record, so the desktop capture
  correctly shows a read-only fact state. The `APPLIED` review state and its approve/reject boundary
  are covered by the component test with realistic projection data.
- Status accents use management-scoped neutral sage and warm-paper tokens. They no longer inherit a
  saturated processing blue from an unrelated product surface.
- At `390px`, the same DOM reduces to one `374.67px` column in the order filter, queue, detail. All
  fact labels remain rendered; no critical field is hidden and document width stays within `375px`.
- Desktop and mobile browser passes covered the management home, catalog, inventory, fulfillment,
  after-sales, marketing, governance, chat, review governance, forbidden and not-found routes.
  A direct chat-detail state and authenticated login redirect were also exercised.
- No tested route produced document-level horizontal overflow. Desktop authenticated routes use the
  new top shell and expose no `.admin-nav` legacy sidebar.

## Code and regression checks

- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `17` files, `90` tests.
- `pnpm --filter @plain-journal/admin-web build` passed.
- `git diff --check` passed.
- The new shell, workbench and migrated page contain no `!important`, `.admin-layout` or
  `.admin-nav` compatibility rules.

## Severity review

- P0: none.
- P1: none.
- P2: none.
- P3: the selected concept includes customer-evidence thumbnails and a dense historical timeline;
  the current Trade projection does not expose those facts, so the implementation deliberately
  leaves them out instead of displaying invented data.

final result: passed

## 2026-09-01 Admin First-pass Batch Regression

- The completed management batch was re-opened in the real in-app browser at `/`, `/catalog`,
  `/fulfillment`, `/after-sales`, `/inventory`, `/marketing`, `/governance`, `/chat` and `/reviews`.
  Every route was inspected at `1440 x 1000` and `390 x 844` rather than inferred from source code.
- Batch contact sheets are preserved at
  `docs/assets/frontend-audit/2026-09-01-admin-batch-regression/desktop-contact-sheet.png` and
  `docs/assets/frontend-audit/2026-09-01-admin-batch-regression/mobile-contact-sheet.png`.
  Each individual desktop and mobile capture remains beside those sheets.
- All nine mobile documents measured `375px` wide in a `375px` layout viewport; all nine desktop
  documents measured `1425px` wide in a `1425px` layout viewport. Root overflow was `0px` for every
  route, with no hidden task facts or broken region ordering.
- The final E2E pass found and closed two issues rather than suppressing them: the processing status
  text now clears WCAG AA contrast, and review command feedback now belongs to the workbench detail
  region so a confirmed moderation result remains visible after the resolved report leaves the OPEN
  queue.
- V6.4 browser recovery coverage passed `17 / 17`, including Governance, Fulfillment, Inventory,
  Marketing, Catalog, After-sale, Review and Chat authority/replay paths.
- `pnpm check:boundaries` passed all `28` checks across `152` layered files and `297` relative
  imports. Admin unit tests passed `23` files / `96` tests; typecheck, production build and
  `git diff --check` also passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Chat Three-Region Workspace Migration

- `ChatWorkspaceView.vue` now owns three sibling regions: Chat authority and realtime scope,
  the OPEN conversation queue, and the selected private thread. It does not import another
  management page or shared business implementation, and it no longer nests queue, claim or
  thread content inside generic card surfaces.
- The selected management reference and the final populated thread were judged together in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/53-chat-source-implementation-comparison.png`.
  Final evidence is preserved in `49-chat-after-desktop-thread.png`,
  `50-chat-after-mobile-top.png`, `51-chat-after-mobile-queue.png` and
  `52-chat-after-mobile-thread.png`.
- The real browser fixture exposed one unassigned conversation. The page kept message content
  unavailable before membership, then the existing claim command confirmed the operator fact,
  loaded MySQL history, advanced the read position and accepted a durable staff reply through the
  original owner-domain API.
- At `390 x 844`, the same DOM reduces in the order scope, queue, thread. The queue remains
  selectable, the active status and four conversation facts remain visible, and the message log
  and composer stay in the thread region without duplicating or hiding their desktop state.
- The migrated page contains no `PjSurface`, `PjPageContainer`, legacy `.chat-page`,
  `.chat-workspace` or `.chat-hero` selector, `!important` rule or hard-coded legacy blue.
- `pnpm --filter @plain-journal/admin-web test` passed: `23` files, `96` tests.
- `pnpm --filter @plain-journal/admin-web typecheck`,
  `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Marketing Parallel Command Workspace Migration

- `MarketingWorkspaceView.vue` now uses a page-owned three-region command workspace: authority and
  recovery context, rule creation, and benefit grant. It does not import a transaction/list
  workbench or another page's business implementation, so this command-only surface can evolve
  without coupling unrelated management domains.
- Source and implementation were judged together at `1280 x 900` in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/39-marketing-source-implementation-comparison.png`.
  Final evidence is preserved in `35-marketing-after-desktop.png`,
  `36-marketing-after-mobile-top.png`, `37-marketing-after-mobile-rule.png` and
  `38-marketing-after-mobile-grant.png`.
- The desktop implementation carries the selected reference's parallel scanning rhythm without
  inventing a rule list that the current API cannot supply. Both real commands and all original
  labels, identifiers, payload fields and recovery boundaries remain visible.
- The real browser fixture exercised the committed-response-lost grant path. The first submit
  entered the result-unknown state; replay with the original `grantKey` returned the authoritative
  `AVAILABLE` benefit fact. A narrow status notice discovered during that pass was corrected at the
  page-owned notice selector by stacking only its local action region.
- At `390 x 844`, the same DOM reduces in the order scope, rule creation, benefit grant. No form is
  duplicated, hidden or transformed into a nested card, and the mobile captures show every field
  retaining the same semantic section as desktop.
- The migrated page contains no `PjSurface`, `PjPageContainer`, legacy `.marketing-page`,
  `!important` rule or hard-coded legacy blue.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `21` files, `94` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Governance Parallel Task Workspace Migration

- `GovernanceWorkspaceView.vue` now owns a three-region governance layout: scope and authority,
  four-domain read-only reconciliation, and the selected Payment compensation command. It does not
  import transaction/list workbenches or another page's business implementation.
- Source and implementation were judged together at `1280 x 900` in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/46-governance-source-implementation-comparison.png`.
  Final evidence is preserved in `42-governance-after-desktop.png`,
  `43-governance-after-mobile-top.png`, `44-governance-after-mobile-reconciliation.png` and
  `45-governance-after-mobile-command.png`.
- The former long vertical page no longer presents reconciliation, refund retry and payment
  exception refund as one false process. Reconciliation stays visible as one read-only region;
  the two parallel compensation commands use an explicit tab switch and retain independent state.
- The real browser `audit-confirmed` fixture exercised both lost-response flows. Refund retry and
  payment exception refund each entered the unknown state, preserved their original command ID,
  and converged only after the authority audit returned an accepted record.
- At `390 x 844`, the same DOM reduces in the order scope, reconciliation, compensation command.
  The four owner domains remain visible, command tabs remain explicit and all active command fields
  stay within their section without nested cards.
- The migrated page contains no `PjSurface`, `PjPageContainer`, legacy `.governance-page`,
  `!important` rule or hard-coded legacy blue. The governance end-to-end flow now selects the
  parallel exception-refund tab before interacting with that command.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `22` files, `95` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Catalog List Workspace Migration

- `CatalogReadOnlyView.vue` now uses a dedicated `ListWorkbench` layout grammar: filters, a
  comparable product list and the selected public projection detail. It does not import
  `SplitWorkbench` or any transaction-page business implementation, so the two management layout
  families can evolve independently.
- The source and implementation were judged together at the same `1280 x 900` CSS viewport in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/24-catalog-source-implementation-comparison.png`.
  Final evidence is preserved in `20-catalog-after-desktop.png`,
  `21-catalog-after-mobile-top.png`, `22-catalog-after-mobile-list.png` and
  `23-catalog-after-mobile-detail.png`.
- The real browser fixture exposed two ACTIVE products. Selecting either row updates the detail
  projection; filtering by `青灰` reduces the list to one result and clearing the filter restores
  both results without inventing write capability.
- The first browser pass exposed a child-image overflow and a wrapped list heading. Both were fixed
  at their owning component selectors; the final desktop document is `1265px` wide inside a
  `1280px` viewport and the detail image keeps a square slot.
- At `390 x 844`, filters, list and detail remain the same DOM in that order. The document width is
  `375px`, with no hidden product facts or horizontal overflow.
- Keyboard focus resolves to the page-scoped moss token (`rgb(102, 112, 100)`), not the legacy
  saturated blue. The migrated page contains no `PjSurface` stack or `!important` rule.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `19` files, `92` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Fulfillment Workspace Migration

- `FulfillmentWorkspaceView.vue` now uses the approved management grammar without importing the
  after-sale or review business implementations. The shared component supplies only the task rail,
  queue and detail regions; Fulfillment retains its own filters, command forms, recovery rules and
  page-scoped selectors.
- Source and implementation were compared at the same `1280 x 900` CSS viewport in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/17-fulfillment-source-implementation-comparison.png`.
  The implementation evidence is preserved in `14-fulfillment-after-desktop.png`,
  `15-fulfillment-after-mobile-top.png` and `16-fulfillment-after-mobile-detail.png`.
- The former long page has been separated into three task modes: forward fulfillment, reverse
  returns and nearby logistics. Only the selected mode contributes a queue and detail surface, so
  parallel facts are no longer presented as one false vertical process.
- A real mock Fulfillment fact in `PICKING`, a real return receipt in `WAIT_SHIPMENT`, and a real
  nearby-position query were exercised in the in-app browser. The query returned the latest
  `TRANSIT` position, while the detail pane continued to identify MySQL as the fact source and
  Redis GEO as a rebuildable projection.
- Eligible commands remain state-driven: the inspected forward fact exposes `确认打包` and
  `标记履约异常`; the inspected waiting-return fact correctly exposes no warehouse command.
  Stable command IDs, result-unknown recovery, authority rereads and original-command retries still
  delegate to the existing fulfillment store.
- The desktop pass initially exposed horizontal drift from an unbroken order identifier in the
  detail heading. The identifier was moved to the wrapping eyebrow and the semantic heading was
  shortened; the final `1280px` viewport reports a `1265px` document width with no horizontal
  overflow.
- At `390 x 844`, the same DOM stacks rail, queue and detail into a `375px` document. Forward and
  reverse selectors, status filters, fact fields, eligible actions, history, traces and GEO controls
  remain rendered; no critical fact is hidden for the mobile layout.
- The migrated page contains no `PjSurface` stack, legacy `.fulfillment-page`/`.fulfillment-record`
  selectors or `!important` rule.
- Browser inspection found that the default design-system focus token still resolved to the legacy
  saturated blue on this page. The workbench now remaps that semantic token to its existing moss
  brand color, preserving a visible keyboard focus ring without introducing a one-off border rule.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test -- --run` passed: `19` files, `92` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Review Governance Migration

- The second real management page now validates that `SplitWorkbench` shares only layout grammar:
  `ReviewWorkspaceView.vue` keeps its own Catalog projection, fields, command forms and page-scoped
  presentation rules instead of importing after-sale business code or page selectors.
- Source and implementation were compared at the same `1280 x 900` CSS viewport in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/12-reviews-source-implementation-comparison.png`.
- The desktop implementation retains the approved `208px / 435.20px / 621.47px` task rail, queue
  and detail columns. It contains no legacy `.review-page`, `.review-record`, `PjSurface` stack or
  management-side navigation card.
- A real mock Catalog report was loaded in the browser. Queue selection, rating, reason, identifiers,
  status, timestamps, public review, reporter detail, stable reply command and stable moderation
  command were all present in their correct regions.
- At `390 x 844`, the workbench reduces to one `374.67px` column. All seven fact labels, the public
  evidence, reply control, moderation control and both submit actions remain rendered; document
  width stays within `375px`.
- The empty OPEN/RESOLVED states and the populated OPEN state were both inspected. Component tests
  also verify that a RESOLVED report removes both write forms rather than merely disabling them.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `18` files, `91` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Inventory List Workspace Migration

- `InventoryWorkspaceView.vue` now follows the list-workspace grammar without importing Catalog or
  transaction business implementations: authority scope, selectable warehouse list and one
  warehouse-bound stock detail context.
- Source and implementation were judged together at `1280 x 900` in
  `docs/assets/frontend-audit/2026-09-01-admin-foundation/32-inventory-source-implementation-comparison.png`.
  Final evidence is preserved in `27-inventory-after-desktop.png`,
  `28-inventory-after-mobile-top.png`, `29-inventory-after-mobile-list.png`,
  `30-inventory-after-mobile-detail.png` and `31-inventory-after-mobile-adjustment.png`.
- The real browser fixture exposed `HZ_MAIN / 杭州主仓`. Selecting the warehouse supplies the same
  warehouse ID to lookup and adjustment contexts. An authority lookup returned on-hand `10`,
  reserved `2`, available `8` and version `3`.
- The complete committed-response-lost recovery path was exercised: adjustment submission remained
  unknown, authority reread reported on-hand `15` without attributing it to a movement, and replay
  with the original movement number confirmed the adjustment. No second command identity was made.
- The desktop document is `1265px` wide inside a `1280px` viewport. At `390 x 844`, authority scope,
  warehouse list, create form, stock lookup, all four stock facts and the full adjustment form remain
  in the same DOM order; the document width is `375px` and all nine controls remain rendered.
- Keyboard focus resolves to the moss semantic token (`rgb(102, 112, 100)`). The migrated page
  contains no `PjSurface`, `PjPageContainer`, legacy `.inventory-page` or `!important` rule.
- `pnpm --filter @plain-journal/admin-web typecheck` passed.
- `pnpm --filter @plain-journal/admin-web test` passed: `20` files, `93` tests.
- `pnpm --filter @plain-journal/admin-web build` and `git diff --check` passed.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed

## 2026-09-01 Admin Special Pages

- The staff login, forbidden and unknown-route states were inspected in the real in-app browser at
  `1440 x 1000` and `390 x 844` rather than inferred from their templates.
- Login and forbidden already use the approved low-density special-page grammar: one controlled
  text column, one explicit explanation and one primary action. They required no structural or
  visual override.
- The unknown-route page was the remaining historical fragment. It now states what happened, what
  remains true about role-scoped access, and offers one safe return. It reuses the existing
  borderless special-page foundation instead of adding a third page shell or nested card.
- Before/after and unchanged-route evidence is preserved in
  `docs/assets/frontend-audit/2026-09-01-special-pages/`. The mobile 404 and forbidden documents
  report no root horizontal overflow.
- `NotFoundView.test.ts` locks the message hierarchy and the single safe return action.
- The completed batch passed `17 / 17` V6.4 E2E scenarios, `24` admin test files / `97` tests,
  `28` layer-boundary checks, typecheck, production build and `git diff --check`.

Severity: P0 none; P1 none; P2 none; P3 none.

final result: passed
