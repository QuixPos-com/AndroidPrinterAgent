import os
import json
import math
from PIL import Image, ImageDraw, ImageFilter

def create_master_art(size=1024):
    """Generates the square base artwork at supersampled 2048x2048 and downscales to size."""
    scale = 2
    w = size * scale
    h = size * scale

    # Master canvas
    img = Image.new("RGBA", (w, h), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)

    # Modern gradient background: Deep sleek navy slate to vibrant Quix blue
    for y in range(h):
        ratio_y = y / float(h)
        for x in range(0, w, 4):
            ratio_x = x / float(w)
            t = (ratio_x * 0.35 + ratio_y * 0.65)
            r = int(15 * (1 - t) + 2 * t)
            g = int(23 * (1 - t) + 132 * t)
            b = int(42 * (1 - t) + 199 * t)
            draw.rectangle([x, y, x + 3, y], fill=(r, g, b, 255))

    # Soft radial glow behind the printer & receipt
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = w // 2, int(h * 0.44)
    max_radius = int(w * 0.52)
    for rad in range(max_radius, 0, -16):
        alpha = int(48 * (1 - rad / max_radius))
        glow_draw.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(56, 189, 248, alpha))
    glow = glow.filter(ImageFilter.GaussianBlur(32 * scale))
    img = Image.alpha_composite(img, glow)
    draw = ImageDraw.Draw(img)

    # Dimensions for Printer & Receipt
    pw = int(w * 0.64)       # printer width
    ph = int(h * 0.39)       # printer base height
    px0 = (w - pw) // 2
    px1 = px0 + pw
    py1 = int(h * 0.77)      # printer bottom
    py0 = py1 - ph           # printer top

    # --- Receipt Paper sticking out of the top slot ---
    rw = int(pw * 0.70)
    rh = int(h * 0.33)
    rx0 = (w - rw) // 2
    rx1 = rx0 + rw
    ry1 = py0 + int(24 * scale)
    ry0 = ry1 - rh

    # Receipt drop shadow
    rec_shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    r_sdraw = ImageDraw.Draw(rec_shadow)
    r_sdraw.rounded_rectangle([rx0, ry0 + 10 * scale, rx1, ry1 + 10 * scale], radius=16 * scale, fill=(0, 0, 0, 110))
    rec_shadow = rec_shadow.filter(ImageFilter.GaussianBlur(14 * scale))
    img = Image.alpha_composite(img, rec_shadow)
    draw = ImageDraw.Draw(img)

    # Receipt paper
    draw.rounded_rectangle([rx0, ry0, rx1, ry1], radius=14 * scale, fill=(248, 250, 252, 255))

    # Brand header stripe on receipt
    bar_y0 = ry0 + int(24 * scale)
    bar_h = int(14 * scale)
    draw.rounded_rectangle([rx0 + int(22 * scale), bar_y0, rx1 - int(22 * scale), bar_y0 + bar_h], radius=6 * scale, fill=(2, 132, 199, 255))

    # Receipt item lines
    l1_y = bar_y0 + bar_h + int(18 * scale)
    draw.rounded_rectangle([rx0 + int(24 * scale), l1_y, rx1 - int(24 * scale), l1_y + int(8 * scale)], radius=4 * scale, fill=(148, 163, 184, 255))

    l2_y = l1_y + int(18 * scale)
    draw.rounded_rectangle([rx0 + int(24 * scale), l2_y, rx0 + int(rw * 0.55), l2_y + int(8 * scale)], radius=4 * scale, fill=(203, 213, 225, 255))
    draw.rounded_rectangle([rx1 - int(rw * 0.28), l2_y, rx1 - int(24 * scale), l2_y + int(8 * scale)], radius=4 * scale, fill=(56, 189, 248, 255))

    l3_y = l2_y + int(18 * scale)
    draw.rounded_rectangle([rx0 + int(24 * scale), l3_y, rx0 + int(rw * 0.45), l3_y + int(8 * scale)], radius=4 * scale, fill=(203, 213, 225, 255))
    draw.rounded_rectangle([rx1 - int(rw * 0.32), l3_y, rx1 - int(24 * scale), l3_y + int(8 * scale)], radius=4 * scale, fill=(56, 189, 248, 255))

    # Barcode representation on receipt
    code_y = l3_y + int(22 * scale)
    code_h = int(18 * scale)
    curr_x = rx0 + int(24 * scale)
    barcode_end = rx1 - int(24 * scale)
    bar_pattern = [4, 2, 6, 3, 2, 5, 3, 7, 2, 4, 3, 6, 2, 5, 4, 2, 6, 3, 4]
    p_idx = 0
    while curr_x < barcode_end:
        b_w = int(bar_pattern[p_idx % len(bar_pattern)] * scale * 0.85)
        b_gap = int(3 * scale)
        if curr_x + b_w > barcode_end:
            b_w = barcode_end - curr_x
        if b_w > 0:
            draw.rectangle([curr_x, code_y, curr_x + b_w, code_y + code_h], fill=(71, 85, 105, 240))
        curr_x += b_w + b_gap
        p_idx += 1

    # --- Printer Shadow ---
    p_shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ps_draw = ImageDraw.Draw(p_shadow)
    ps_draw.rounded_rectangle([px0 - 6 * scale, py0 + 16 * scale, px1 + 6 * scale, py1 + 24 * scale], radius=38 * scale, fill=(0, 0, 0, 150))
    p_shadow = p_shadow.filter(ImageFilter.GaussianBlur(26 * scale))
    img = Image.alpha_composite(img, p_shadow)
    draw = ImageDraw.Draw(img)

    # --- Printer Body ---
    body_fill = (30, 41, 59, 255)
    body_radius = 34 * scale
    draw.rounded_rectangle([px0, py0, px1, py1], radius=body_radius, fill=body_fill)
    draw.rounded_rectangle([px0, py0, px1, py1], radius=body_radius, outline=(71, 85, 105, 255), width=int(3 * scale))

    # Slot Bezel
    slot_y0 = py0 + int(14 * scale)
    slot_h = int(24 * scale)
    slot_w = int(pw * 0.82)
    slot_x0 = (w - slot_w) // 2
    slot_x1 = slot_x0 + slot_w
    draw.rounded_rectangle([slot_x0, slot_y0, slot_x1, slot_y0 + slot_h], radius=10 * scale, fill=(10, 15, 26, 255))
    draw.rounded_rectangle([slot_x0, slot_y0, slot_x1, slot_y0 + slot_h], radius=10 * scale, outline=(51, 65, 85, 255), width=int(2 * scale))

    # Inner Paper Slot slit
    draw.rectangle([slot_x0 + int(10 * scale), slot_y0 + int(7 * scale), slot_x1 - int(10 * scale), slot_y0 + slot_h - int(7 * scale)], fill=(2, 6, 23, 255))

    # Front Panel Divider
    div_y = slot_y0 + slot_h + int(20 * scale)
    draw.line([px0 + int(22 * scale), div_y, px1 - int(22 * scale), div_y], fill=(51, 65, 85, 220), width=int(2 * scale))

    # 1. Status LED (Right)
    led_x = px1 - int(52 * scale)
    led_y = div_y + int(50 * scale)
    led_r = int(11 * scale)

    led_glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    lg_draw = ImageDraw.Draw(led_glow)
    lg_draw.ellipse([led_x - led_r * 2.5, led_y - led_r * 2.5, led_x + led_r * 2.5, led_y + led_r * 2.5], fill=(34, 197, 94, 95))
    led_glow = led_glow.filter(ImageFilter.GaussianBlur(8 * scale))
    img = Image.alpha_composite(img, led_glow)
    draw = ImageDraw.Draw(img)

    draw.ellipse([led_x - led_r, led_y - led_r, led_x + led_r, led_y + led_r], fill=(34, 197, 94, 255), outline=(134, 239, 172, 255), width=int(2 * scale))

    # 2. Wireless/BLE waves (Left)
    wave_cx = px0 + int(56 * scale)
    wave_cy = led_y
    draw.ellipse([wave_cx - 4 * scale, wave_cy - 4 * scale, wave_cx + 4 * scale, wave_cy + 4 * scale], fill=(56, 189, 248, 255))
    for arc_r in [15 * scale, 26 * scale]:
        arc_box = [wave_cx - arc_r, wave_cy - arc_r, wave_cx + arc_r, wave_cy + arc_r]
        draw.arc(arc_box, start=-50, end=50, fill=(56, 189, 248, 230), width=int(3 * scale))

    # 3. Central Brand Accent: Stylized "Q" Badge
    badge_cx = w // 2
    badge_cy = div_y + int(50 * scale)
    badge_r = int(38 * scale)

    draw.ellipse([badge_cx - badge_r, badge_cy - badge_r, badge_cx + badge_r, badge_cy + badge_r], fill=(15, 23, 42, 255), outline=(56, 189, 248, 220), width=int(3 * scale))

    q_ring_r = int(19 * scale)
    draw.ellipse([badge_cx - q_ring_r, badge_cy - q_ring_r - 2 * scale, badge_cx + q_ring_r, badge_cy + q_ring_r - 2 * scale], outline=(255, 255, 255, 255), width=int(5 * scale))
    draw.line([badge_cx + int(5 * scale), badge_cy + int(2 * scale), badge_cx + int(21 * scale), badge_cy + int(19 * scale)], fill=(56, 189, 248, 255), width=int(6 * scale))

    # Feed Button
    feed_w = int(pw * 0.32)
    feed_h = int(8 * scale)
    feed_y = py1 - int(26 * scale)
    draw.rounded_rectangle([(w - feed_w) // 2, feed_y, (w + feed_w) // 2, feed_y + feed_h], radius=4 * scale, fill=(51, 65, 85, 255))

    final_master = img.resize((size, size), Image.Resampling.LANCZOS)
    return final_master

def render_android_squircle(master_art, size):
    """Renders Android squircle icon with standard padding and subtle elevation drop shadow."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    
    # Android legacy icon standard safe zone: ~84% of canvas
    icon_box_size = int(size * 0.84)
    pad = (size - icon_box_size) // 2
    
    # Scale master art
    art_scaled = master_art.resize((icon_box_size, icon_box_size), Image.Resampling.LANCZOS)
    
    # Squircle mask (radius ~22%)
    mask = Image.new("L", (icon_box_size * 2, icon_box_size * 2), 0)
    mdraw = ImageDraw.Draw(mask)
    r = int(icon_box_size * 2 * 0.22)
    mdraw.rounded_rectangle([0, 0, icon_box_size * 2, icon_box_size * 2], radius=r, fill=255)
    mask = mask.resize((icon_box_size, icon_box_size), Image.Resampling.LANCZOS)
    
    masked_art = Image.new("RGBA", (icon_box_size, icon_box_size), (0, 0, 0, 0))
    masked_art.paste(art_scaled, (0, 0), mask)
    
    # Drop shadow
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    shadow_y_offset = max(1, int(size * 0.025))
    sdraw.rounded_rectangle(
        [pad, pad + shadow_y_offset, pad + icon_box_size, pad + icon_box_size + shadow_y_offset],
        radius=int(icon_box_size * 0.22),
        fill=(0, 0, 0, 95)
    )
    blur_r = max(1, int(size * 0.03))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur_r))
    
    canvas.paste(shadow, (0, 0), shadow)
    canvas.paste(masked_art, (pad, pad), masked_art)
    return canvas

def render_android_round(master_art, size):
    """Renders Android round launcher icon with circle mask, padding, and subtle elevation shadow."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    
    icon_box_size = int(size * 0.92)
    pad = (size - icon_box_size) // 2
    
    art_scaled = master_art.resize((icon_box_size, icon_box_size), Image.Resampling.LANCZOS)
    
    mask = Image.new("L", (icon_box_size * 2, icon_box_size * 2), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.ellipse([0, 0, icon_box_size * 2, icon_box_size * 2], fill=255)
    mask = mask.resize((icon_box_size, icon_box_size), Image.Resampling.LANCZOS)
    
    masked_art = Image.new("RGBA", (icon_box_size, icon_box_size), (0, 0, 0, 0))
    masked_art.paste(art_scaled, (0, 0), mask)
    
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    shadow_y_offset = max(1, int(size * 0.02))
    sdraw.ellipse(
        [pad, pad + shadow_y_offset, pad + icon_box_size, pad + icon_box_size + shadow_y_offset],
        fill=(0, 0, 0, 90)
    )
    blur_r = max(1, int(size * 0.025))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur_r))
    
    canvas.paste(shadow, (0, 0), shadow)
    canvas.paste(masked_art, (pad, pad), masked_art)
    return canvas

def generate_all_icons():
    print("Generating 1024x1024 master artwork...")
    master = create_master_art(1024)
    master.save("master_icon_1024.png", "PNG")

    # Android mipmaps
    android_densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    base_res_dir = r"android\app\src\main\res"
    for folder, dim in android_densities.items():
        folder_path = os.path.join(base_res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)

        # Standard squircle
        squircle_icon = render_android_squircle(master, dim)
        squircle_path = os.path.join(folder_path, "ic_launcher.png")
        squircle_icon.save(squircle_path, "PNG")

        # Round icon
        round_icon = render_android_round(master, dim)
        round_path = os.path.join(folder_path, "ic_launcher_round.png")
        round_icon.save(round_path, "PNG")
        print(f"Saved Android {folder} ({dim}x{dim})")

    # iOS AppIcon.appiconset
    ios_icon_dir = r"ios\PrinterAgentApp\Images.xcassets\AppIcon.appiconset"
    os.makedirs(ios_icon_dir, exist_ok=True)

    ios_entries = [
        {"idiom": "iphone", "size": "20x20", "scale": "2x", "dim": 40, "filename": "icon_20x20@2x.png"},
        {"idiom": "iphone", "size": "20x20", "scale": "3x", "dim": 60, "filename": "icon_20x20@3x.png"},
        {"idiom": "iphone", "size": "29x29", "scale": "2x", "dim": 58, "filename": "icon_29x29@2x.png"},
        {"idiom": "iphone", "size": "29x29", "scale": "3x", "dim": 87, "filename": "icon_29x29@3x.png"},
        {"idiom": "iphone", "size": "40x40", "scale": "2x", "dim": 80, "filename": "icon_40x40@2x.png"},
        {"idiom": "iphone", "size": "40x40", "scale": "3x", "dim": 120, "filename": "icon_40x40@3x.png"},
        {"idiom": "iphone", "size": "60x60", "scale": "2x", "dim": 120, "filename": "icon_60x60@2x.png"},
        {"idiom": "iphone", "size": "60x60", "scale": "3x", "dim": 180, "filename": "icon_60x60@3x.png"},
        {"idiom": "ios-marketing", "size": "1024x1024", "scale": "1x", "dim": 1024, "filename": "icon_1024x1024.png"},
    ]

    contents_images = []
    for entry in ios_entries:
        dim = entry["dim"]
        fname = entry["filename"]
        out_path = os.path.join(ios_icon_dir, fname)
        # iOS icons are full bleed squares (RGB, no transparency)
        ios_img = master.resize((dim, dim), Image.Resampling.LANCZOS).convert("RGB")
        ios_img.save(out_path, "PNG")
        contents_images.append({
            "filename": fname,
            "idiom": entry["idiom"],
            "scale": entry["scale"],
            "size": entry["size"]
        })
        print(f"Saved iOS AppIcon {fname} ({dim}x{dim})")

    contents_json = {
        "images": contents_images,
        "info": {
            "author": "xcode",
            "version": 1
        }
    }

    with open(os.path.join(ios_icon_dir, "Contents.json"), "w", encoding="utf-8") as f:
        json.dump(contents_json, f, indent=2)
    print("Updated iOS Contents.json")

if __name__ == "__main__":
    generate_all_icons()
