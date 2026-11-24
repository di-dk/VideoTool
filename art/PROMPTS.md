# Art prompts (Qwen-Image 2.1 turbo, workflow-qwen-image.json; 12 steps, euler/simple, cfg 1, "<PROMPT>" and seed are placeholders in the JSON)

Background colour used for icon-1024.png / adaptive-icon background layer: #2B1B6B
(raw model output was ~#0A0F76; icon-1024.png was recomposited from the keyed cutout onto exact #2B1B6B, banner and empty-state keep the model's raw indigo.)

## icon (seed 201, 1024x1024, raw/icon2) -> icon-1024.png, icon-foreground-1024.png (keyed out with numpy, recentred)
Flat vector app icon illustration, perfectly centered on a solid flat deep indigo background (#2B1B6B), uniform color, no gradient, no vignette. A single bold rounded-square film frame with a big white play triangle in the middle, outlined in thick bright cyan. A bright magenta wrench crossed diagonally behind the lower-right corner of the frame as a toolbox accent. Bold flat shapes, only four colors: deep indigo, cyan, magenta, white. Large empty margin around the subject, subject occupies only the central half. No text, no letters, no shadow, no gradient.

## banner (seed 302, 1216x640 resized to 1200x630)
Wide flat vector banner on a solid deep indigo background. The entire left 45 percent of the image is plain empty indigo. In the right 50 percent, vertically centered with margin on all sides: a diagonal cyan film strip with sprocket holes flowing across, a magenta timeline bar with a white playhead, and three round slider knobs in cyan and magenta on thin tracks, all fully inside the frame and not touching any edge. Bold flat geometric shapes, only indigo, cyan, magenta and white. No text, no letters, no gradients, no shadows.
(attempt 1, seed 301, rejected: subject cut off at right edge, low contrast)

## empty state (seed 401, 1024x1024 resized to 512)
Friendly flat vector illustration on a solid deep indigo background (#2B1B6B), centered subject with generous margin: a cute rounded film clapper-board character-free icon-style scene: a large dashed cyan outlined rounded rectangle drop zone containing a white play triangle, with a magenta plus-sign circle badge on its corner and a small cyan film strip tilted beside it. Bold flat shapes, only deep indigo, cyan, magenta and white, inviting and simple. No text, no letters, no gradients, no shadows.

(An earlier discarded FrameBoost-concept icon, seed 101, was not used.)
