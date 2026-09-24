const MAX_WIDTH = 720;
const MAX_HEIGHT = 1280;
const TARGET_BYTES = 300 * 1024;
const QUALITY_STEPS = [0.78, 0.7, 0.62, 0.54, 0.46];

export const fitCoverDimensions = (width: number, height: number) => {
  const scale = Math.min(1, MAX_WIDTH / width, MAX_HEIGHT / height);
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
};

export const coverFilename = (name: string) => {
  const dot = name.lastIndexOf('.');
  return `${dot > 0 ? name.slice(0, dot) : name}.webp`;
};

const canvasBlob = (canvas: HTMLCanvasElement, quality: number) => new Promise<Blob | null>((resolve) => {
  canvas.toBlob(resolve, 'image/webp', quality);
});

export const optimizeCoverFile = async (file: File): Promise<File> => {
  if (!file.type.startsWith('image/') || typeof createImageBitmap !== 'function' || typeof document === 'undefined') {
    return file;
  }
  let bitmap: ImageBitmap | undefined;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const dimensions = fitCoverDimensions(bitmap.width, bitmap.height);
    if (file.type === 'image/webp'
      && file.size <= TARGET_BYTES
      && dimensions.width === bitmap.width
      && dimensions.height === bitmap.height) return file;

    const canvas = document.createElement('canvas');
    canvas.width = dimensions.width;
    canvas.height = dimensions.height;
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.drawImage(bitmap, 0, 0, dimensions.width, dimensions.height);

    let best: Blob | null = null;
    for (const quality of QUALITY_STEPS) {
      const candidate = await canvasBlob(canvas, quality);
      if (!candidate || candidate.type !== 'image/webp') continue;
      if (!best || candidate.size < best.size) best = candidate;
      if (candidate.size <= TARGET_BYTES) break;
    }
    if (!best) return file;
    return new File([best], coverFilename(file.name), {
      type: 'image/webp',
      lastModified: file.lastModified
    });
  } catch {
    return file;
  } finally {
    bitmap?.close();
  }
};
