package moe.grass.webgpuviewer

object WebGpuRendererShader {
    val shader = """
struct Uniforms {
    offset: vec2<f32>,
    scale: f32,
    tile_size: f32,
    tiles_width: f32,
    tiles_height: f32,
}

@group(0) @binding(0) var dst_tex: texture_storage_2d<rgba8unorm, write>;
@group(0) @binding(1) var<uniform> transform: Uniforms;
@group(0) @binding(2) var src_tex0: texture_2d<f32>;
@group(0) @binding(3) var src_tex1: texture_2d<f32>;
@group(0) @binding(4) var src_tex2: texture_2d<f32>;
@group(0) @binding(5) var src_tex3: texture_2d<f32>;

fn tileLoad(i: i32, pos: vec2<i32>) -> vec4<f32> {
    if (i == 0) { return textureLoad(src_tex0, pos, 0); }
    if (i == 1) { return textureLoad(src_tex1, pos, 0); }
    if (i == 2) { return textureLoad(src_tex2, pos, 0); }
    return textureLoad(src_tex3, pos, 0);
}

fn tileDimensions(i: i32) -> vec2<u32> {
    if (i == 0) { return textureDimensions(src_tex0); }
    if (i == 1) { return textureDimensions(src_tex1); }
    if (i == 2) { return textureDimensions(src_tex2); }
    return textureDimensions(src_tex3);
}

fn totalDimensions() -> vec2<u32> {
    var width: u32 = 0;
    var height: u32 = 0;

    for (var x: i32 = 0; x < i32(transform.tiles_width); x++) {
        width += tileDimensions(x).x;
    }

    for (var y: i32 = 0; y < i32(transform.tiles_height); y++) {
        height += tileDimensions(y * 2).y;
    }

    return vec2<u32>(width, height);
}

fn totalLoad(pos: vec2<i32>) -> vec4<f32> {
    var idx = pos.y / i32(transform.tile_size) * 2 + pos.x / i32(transform.tile_size);
    var pos0 = pos % i32(transform.tile_size);

    if (pos.x >= i32(transform.tile_size) * 2) { return vec4<f32>(0); }
    if (pos.y >= i32(transform.tile_size) * 2) { return vec4<f32>(0); }
    return tileLoad(idx, pos0);
}

fn to_linear_exact(srgb: vec4<f32>) -> vec4<f32> {
    let c = max(srgb.rgb, vec3<f32>(0.0));
    let lower = c / vec3<f32>(12.92);
    let higher = pow((c + vec3<f32>(0.055)) / vec3<f32>(1.055), vec3<f32>(2.4));
    let cond = c <= vec3<f32>(0.04045);
    return vec4(select(higher, lower, cond), srgb.a);
}

fn to_srgb_exact(linear_rgb: vec4<f32>) -> vec4<f32> {
    let c = max(linear_rgb.rgb, vec3<f32>(0.0));
    let lower = c * vec3<f32>(12.92);
    let higher = vec3<f32>(1.055) * pow(c, vec3<f32>(1.0 / 2.4)) - vec3<f32>(0.055);
    let cond = c <= vec3<f32>(0.0031308);
    return vec4(select(higher, lower, cond), linear_rgb.a);
}

fn to_linear_fast(srgb: vec4<f32>) -> vec4<f32> {
    return vec4(pow(max(srgb.rgb, vec3<f32>(0.0)), vec3<f32>(2.2)), srgb.a);
}

fn to_srgb_fast(linear: vec4<f32>) -> vec4<f32> {
    return vec4(pow(max(linear.rgb, vec3<f32>(0.0)), vec3<f32>(1.0 / 2.2)), linear.a);
}

fn catmull_rom_weights(t: f32) -> array<f32, 4> {
    let t2 = t * t;
    let t3 = t2 * t;

    return array<f32, 4>(
        -0.5 * t3 + t2 - 0.5 * t,          // Weight 0 (Negative lobe)
         1.5 * t3 - 2.5 * t2 + 1.0,        // Weight 1 (Primary influence)
        -1.5 * t3 + 2.0 * t2 + 0.5 * t,    // Weight 2 (Primary influence)
         0.5 * t3 - 0.5 * t2               // Weight 3 (Negative lobe)
    );
}

// Main function: Samples the texture using a 4x4 Catmull-Rom filter
fn textureSampleCatmullRom(uv: vec2<f32>) -> vec4<f32> {
    let tex_size_u = totalDimensions();
    let tex_size = vec2<f32>(tex_size_u);

    let pixel_coord = uv * tex_size - vec2<f32>(0.5);
    let base_coord = vec2<i32>(floor(pixel_coord));
    let f = fract(pixel_coord);

    let wx = catmull_rom_weights(f.x);
    let wy = catmull_rom_weights(f.y);

    let max_coord = vec2<i32>(tex_size_u) - vec2<i32>(1, 1);

    var final_color = vec4<f32>(0.0);

    for (var y: i32 = 0; y < 4; y++) {
        var row_color = vec4<f32>(0.0);

        let current_y = base_coord.y - 1 + y;

        for (var x: i32 = 0; x < 4; x++) {
            let current_x = base_coord.x - 1 + x;

            var texel = vec4<f32>(0.0);

            if (current_x >= 0 && current_x <= max_coord.x &&
                current_y >= 0 && current_y <= max_coord.y) {
                texel = totalLoad(vec2<i32>(current_x, current_y));
                texel = to_linear_exact(texel);
            }

            row_color += texel * wx[x];
        }

        final_color += row_color * wy[y];
    }

    final_color = to_srgb_exact(final_color);
    return clamp(final_color, vec4<f32>(0.0), vec4<f32>(1.0));
}

fn downsample(src_start: vec2<f32>, scale: vec2<f32>) -> vec4<f32> {
    let dst_size = textureDimensions(dst_tex);
    let src_size = totalDimensions();

    let src_size_f = vec2<f32>(src_size);

    let src_end = src_start + vec2<f32>(1) * scale;

    let start_i = vec2<i32>(clamp(floor(src_start), vec2<f32>(0.0), src_size_f));
    let end_i   = vec2<i32>(clamp(ceil(src_end), vec2<f32>(0.0), src_size_f));

    var color_sum = vec4<f32>(0.0);
    var weight_sum = 0.0;

    for (var y: i32 = start_i.y; y < end_i.y; y++) {
        let y_f = f32(y);
        let y_overlap = max(0.0, min(y_f + 1.0, src_end.y) - max(y_f, src_start.y));

        for (var x: i32 = start_i.x; x < end_i.x; x++) {
            let x_f = f32(x);
            let x_overlap = max(0.0, min(x_f + 1.0, src_end.x) - max(x_f, src_start.x));

            let weight = x_overlap * y_overlap;
            var texel = totalLoad(vec2<i32>(x, y));
            texel = to_linear_exact(texel);

            color_sum += texel * weight;
            weight_sum += weight;
        }
    }

    var col = color_sum / weight_sum;
    return to_srgb_exact(col);
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let dst_size = textureDimensions(dst_tex);
    let src_size = totalDimensions();

    if (id.x >= dst_size.x || id.y >= dst_size.y) {
        return;
    }

    let src_size_f = vec2<f32>(src_size);
    let dst_size_f = vec2<f32>(dst_size);

    let scale = vec2(1.0 / (transform.scale));
    let offset = transform.offset;

    if (max(scale.x, scale.y) > 1.0) {
        let col = downsample(vec2<f32>(id.xy) * scale - offset * dst_size_f, scale);
        textureStore(dst_tex, id.xy, col);
    } else {
        let uv = vec2<f32>(id.xy) / src_size_f * scale - offset * dst_size_f / src_size_f;
        let col = textureSampleCatmullRom(uv);
        textureStore(dst_tex, id.xy, col);
    }
}
    """
}
