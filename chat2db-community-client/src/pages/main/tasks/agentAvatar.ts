export async function readAgentAvatar(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error('avatar must be an image');
  if (file.size > 5 * 1024 * 1024) throw new Error('avatar image must be smaller than 5 MB');
  const source = await fileToDataUrl(file);
  const image = await loadImage(source);
  const size = Math.min(image.naturalWidth, image.naturalHeight);
  const canvas = document.createElement('canvas');
  canvas.width = 256;
  canvas.height = 256;
  canvas.getContext('2d')?.drawImage(
    image,
    (image.naturalWidth - size) / 2,
    (image.naturalHeight - size) / 2,
    size,
    size,
    0,
    0,
    256,
    256,
  );
  return canvas.toDataURL('image/webp', 0.82);
}

function fileToDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

function loadImage(source: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = source;
  });
}
