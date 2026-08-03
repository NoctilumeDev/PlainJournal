export interface PjResponsiveImageSource {
  type: "image/avif" | "image/webp";
  srcset: string;
}

export interface PjResponsiveImageDelivery {
  src: string;
  sources: PjResponsiveImageSource[];
  width?: number;
  height?: number;
}

interface BundledCatalogImage {
  width: number;
  height: number;
  widths: readonly number[];
}

const BUNDLED_CATALOG_IMAGES: Readonly<Record<string, BundledCatalogImage>> = {
  "/images/catalog/canvas-commuter-tote.png": {
    width: 1122,
    height: 1402,
    widths: [480, 800, 1122],
  },
  "/images/catalog/mist-blue-notebook.png": {
    width: 1122,
    height: 1402,
    widths: [480, 800, 1122],
  },
};

function replaceExtension(
  source: string,
  width: number,
  extension: "avif" | "webp",
): string {
  return source.replace(/\.png$/u, `-${width}.${extension}`);
}

export function resolveCatalogImageDelivery(
  source: string,
): PjResponsiveImageDelivery {
  const image = BUNDLED_CATALOG_IMAGES[source];
  if (!image) {
    return {
      src: source,
      sources: [],
    };
  }

  return {
    src: source,
    width: image.width,
    height: image.height,
    sources: [
      {
        type: "image/avif",
        srcset: image.widths
          .map((width) => `${replaceExtension(source, width, "avif")} ${width}w`)
          .join(", "),
      },
      {
        type: "image/webp",
        srcset: image.widths
          .map((width) => `${replaceExtension(source, width, "webp")} ${width}w`)
          .join(", "),
      },
    ],
  };
}
