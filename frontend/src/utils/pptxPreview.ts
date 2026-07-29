import { init } from "pptx-preview";

export async function renderPptx(buffer: ArrayBuffer, container: HTMLElement): Promise<void> {
  container.innerHTML = "";
  container.classList.remove("hidden");
  const viewer = init(container, { mode: "slide" });
  await viewer.preview(buffer);
}
