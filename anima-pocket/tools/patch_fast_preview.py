#!/usr/bin/env python3
"""Opt-in Anima latent-to-RGB output for the v0.6.9 hybrid NPU build.

Runs against stable-diffusion.cpp pinned by anima-hybrid-npu.yml.
Leaves the normal TAE / VAE path untouched when the opt-in flag is off.
"""
from pathlib import Path

engine = Path("anima-pocket/app/src/main/cpp/stable-diffusion.cpp")
image = engine / "src/pipeline/image.cpp"
diffusion = engine / "src/pipeline/diffusion_engine.cpp"

def replace_once(path: Path, old: str, new: str) -> None:
    contents = path.read_text()
    if contents.count(old) != 1:
        raise SystemExit(f"Patch mismatch in {path}: expected one anchor; found {contents.count(old)}")
    path.write_text(contents.replace(old, new, 1))

# Anima's 16-channel VAE is Wan-derived. The upstream preview has a
# ready-made 16-channel projection but does not always classify Anima as Wan.
replace_once(
    diffusion,
    "} else if (sd_version_uses_wan_vae(version)) {\n                latent_rgb_proj = wan_21_latent_rgb_proj;",
    "} else if (sd_version_uses_wan_vae(version) || version == VERSION_ANIMA) {\n                latent_rgb_proj = wan_21_latent_rgb_proj;",
)

replace_once(
    image,
    "        std::vector<sd::Tensor<float>> decoded_images;\n        int64_t t0     = ggml_time_ms();",
    r'''        // The preview option is deliberately NOT an image decoder. It applies a
        // fixed learned-channel RGB matrix at latent resolution, then bilinear
        // upscales the result. It is similar to ComfyUI's latent2rgb preview.
        // Without it the normal TAE/VAE path below is unchanged.
        const char* fast_rgb_env = std::getenv("ANIMA_FAST_LATENT_RGB");
        if (sd->version == VERSION_ANIMA && fast_rgb_env && fast_rgb_env[0] == '1') {
            const int target_width = request.width;
            const int target_height = request.height;
            const size_t count = final_latents.size();
            sd_image_t* images = static_cast<sd_image_t*>(calloc(count, sizeof(sd_image_t)));
            if (!images) {
                LOG_ERROR("ANIMA_FAST_LATENT_RGB: output allocation failed");
                return nullptr;
            }
            bool successful = true;
            for (size_t i = 0; i < count && successful; ++i) {
                if (sd->get_cancel_flag() == SD_CANCEL_ALL) {
                    successful = false;
                    break;
                }
                sd_image_t& dst = images[i];
                sd->preview_image(
                    0, final_latents[i], sd->version, PREVIEW_PROJ,
                    [&dst, target_width, target_height](
                        int, int frames, sd_image_t* originals, bool, void*) {
                        if (frames < 1 || !originals || !originals[0].data ||
                            originals[0].channel < 3 || originals[0].width == 0 ||
                            originals[0].height == 0) {
                            return;
                        }
                        const sd_image_t& src = originals[0];
                        const size_t len = static_cast<size_t>(target_width) *
                                           static_cast<size_t>(target_height) * 3;
                        uint8_t* data = static_cast<uint8_t*>(malloc(len));
                        if (!data) return;
                        const float rx = static_cast<float>(src.width) / target_width;
                        const float ry = static_cast<float>(src.height) / target_height;
                        for (int y = 0; y < target_height; ++y) {
                            const float yf = (y + .5f) * ry - .5f;
                            const int y0 = std::max(0, std::min(static_cast<int>(src.height) - 1,
                                                                  static_cast<int>(std::floor(yf))));
                            const int y1 = std::min(static_cast<int>(src.height) - 1, y0 + 1);
                            const float dy = std::max(0.0f, std::min(1.0f, yf - y0));
                            for (int x = 0; x < target_width; ++x) {
                                const float xf = (x + .5f) * rx - .5f;
                                const int x0 = std::max(0, std::min(static_cast<int>(src.width) - 1,
                                                                      static_cast<int>(std::floor(xf))));
                                const int x1 = std::min(static_cast<int>(src.width) - 1, x0 + 1);
                                const float dx = std::max(0.0f, std::min(1.0f, xf - x0));
                                const size_t p00 = (static_cast<size_t>(y0) * src.width + x0) * src.channel;
                                const size_t p01 = (static_cast<size_t>(y0) * src.width + x1) * src.channel;
                                const size_t p10 = (static_cast<size_t>(y1) * src.width + x0) * src.channel;
                                const size_t p11 = (static_cast<size_t>(y1) * src.width + x1) * src.channel;
                                const size_t output = (static_cast<size_t>(y) * target_width + x) * 3;
                                for (int c = 0; c < 3; ++c) {
                                    const float top = src.data[p00 + c] * (1.0f - dx) +
                                                      src.data[p01 + c] * dx;
                                    const float bottom = src.data[p10 + c] * (1.0f - dx) +
                                                         src.data[p11 + c] * dx;
                                    data[output + c] = static_cast<uint8_t>(
                                        std::max(0.0f, std::min(255.0f,
                                            top * (1.0f - dy) + bottom * dy + .5f)));
                                }
                            }
                        }
                        dst.width = target_width;
                        dst.height = target_height;
                        dst.channel = 3;
                        dst.data = data;
                    },
                    nullptr, false);
                if (!dst.data) successful = false;
            }
            if (!successful) {
                for (size_t i = 0; i < count; ++i) free(images[i].data);
                free(images);
                LOG_ERROR("ANIMA_FAST_LATENT_RGB: projection did not produce RGB");
                return nullptr;
            }
            if (num_images_out) *num_images_out = static_cast<int>(count);
            LOG_INFO("ANIMA_FAST_LATENT_RGB: skipped TAE/VAE decode; %zu RGB previews at %dx%d",
                     count, target_width, target_height);
            return images;
        }
        std::vector<sd::Tensor<float>> decoded_images;
        int64_t t0     = ggml_time_ms();'''
)

print("Applied optional Anima latent RGB preview without changing standard decoding")
