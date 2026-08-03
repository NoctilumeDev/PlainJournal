import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import PjButton from "./PjButton.vue";
import PjField from "./PjField.vue";
import PjPageContainer from "./PjPageContainer.vue";
import PjResponsiveImage from "./PjResponsiveImage.vue";
import PjStatusNotice from "./PjStatusNotice.vue";
import PjSurface from "./PjSurface.vue";
import { resolveCatalogImageDelivery } from "./responsiveImage";

describe("Plain Journal UI primitives", () => {
  it("fails closed while a primary action is loading", () => {
    const wrapper = mount(PjButton, {
      props: { loading: true },
      slots: { default: "正在确认" },
    });

    expect(wrapper.attributes("disabled")).toBeDefined();
    expect(wrapper.attributes("aria-busy")).toBe("true");
    expect(wrapper.classes()).toContain("pj-button--loading");
    expect(wrapper.text()).toContain("正在确认");
  });

  it("connects field labels, hints and errors without hiding the control", () => {
    const wrapper = mount(PjField, {
      props: {
        label: "邮箱",
        forId: "email",
        hint: "用于恢复账户",
        error: "邮箱不可用",
        required: true,
      },
      slots: {
        default: '<input id="email" class="pj-control" />',
      },
    });

    expect(wrapper.get("label").attributes("for")).toBe("email");
    expect(wrapper.get("#email-hint").text()).toBe("用于恢复账户");
    expect(wrapper.get("#email-error").attributes("role")).toBe("alert");
  });

  it("uses assertive semantics only for high-risk notices", () => {
    const attention = mount(PjStatusNotice, {
      props: { tone: "attention", title: "需要人工处理" },
      slots: { default: "不能直接改成成功。" },
    });
    const processing = mount(PjStatusNotice, {
      props: { tone: "processing" },
      slots: { default: "正在确认。" },
    });
    const warning = mount(PjStatusNotice, {
      props: { tone: "warning", assertive: true },
      slots: { default: "当前会话仍保留。" },
    });

    expect(attention.attributes("role")).toBe("alert");
    expect(attention.attributes("aria-live")).toBe("assertive");
    expect(processing.attributes("role")).toBe("status");
    expect(processing.attributes("aria-live")).toBe("polite");
    expect(warning.attributes("role")).toBe("alert");
  });

  it("provides one shared width owner for shell and page composition", () => {
    const wrapper = mount(PjPageContainer, {
      props: { size: "reading" },
    });

    expect(wrapper.classes()).toEqual([
      "pj-page-container",
      "pj-page-container--reading",
    ]);
  });

  it("forwards accessible relationships to the selected surface element", () => {
    const wrapper = mount(PjSurface, {
      props: { as: "aside", tone: "raised" },
      attrs: { "aria-labelledby": "summary-title" },
      slots: { default: '<h2 id="summary-title">金额明细</h2>' },
    });

    expect(wrapper.element.tagName).toBe("ASIDE");
    expect(wrapper.attributes("aria-labelledby")).toBe("summary-title");
    expect(wrapper.get("#summary-title").text()).toBe("金额明细");
  });

  it("offers AVIF and WebP before the original catalog fallback", () => {
    const delivery = resolveCatalogImageDelivery(
      "/images/catalog/canvas-commuter-tote.png",
    );
    const wrapper = mount(PjResponsiveImage, {
      props: {
        ...delivery,
        alt: "帆布通勤袋",
        sizes: "50vw",
        loading: "lazy",
      },
    });

    expect(wrapper.findAll("source")).toHaveLength(2);
    expect(wrapper.findAll("source")[0]?.attributes()).toMatchObject({
      type: "image/avif",
      sizes: "50vw",
    });
    expect(wrapper.findAll("source")[0]?.attributes("srcset"))
      .toContain("canvas-commuter-tote-480.avif 480w");
    expect(wrapper.get("img").attributes()).toMatchObject({
      src: "/images/catalog/canvas-commuter-tote.png",
      alt: "帆布通勤袋",
      width: "1122",
      height: "1402",
      loading: "lazy",
      decoding: "async",
    });
  });

  it("does not invent variants for an owner-provided image URL", () => {
    expect(resolveCatalogImageDelivery("https://cdn.example/product.png"))
      .toEqual({
        src: "https://cdn.example/product.png",
        sources: [],
      });
  });
});
