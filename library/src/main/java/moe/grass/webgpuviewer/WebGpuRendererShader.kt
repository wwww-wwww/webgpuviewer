package moe.grass.webgpuviewer

object WebGpuRendererShader {
    val shader = """
struct Uniforms {
    offset: vec2<f32>,
    scale: f32,
    tile_size: f32,
    tiles_width: f32,
    tiles_height: f32,
    dst_width: f32,
    dst_height: f32,
}

@group(0) @binding(0) var<uniform> transform: Uniforms;
@group(0) @binding(1) var src_tex0: texture_2d<f32>;
@group(0) @binding(2) var src_tex1: texture_2d<f32>;
@group(0) @binding(3) var src_tex2: texture_2d<f32>;
@group(0) @binding(4) var src_tex3: texture_2d<f32>;

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var pos = array<vec2<f32>, 3>(
        vec2<f32>(-1.0, -1.0), // Bottom-left
        vec2<f32>( 3.0, -1.0), // Far-right
        vec2<f32>(-1.0,  3.0)  // Far-top
    );

    var out: VertexOutput;
    let p = pos[vertex_index];
    out.position = vec4<f32>(p, 0.0, 1.0);

    out.uv = vec2<f32>(p.x * 0.5 + 0.5, 1.0 - (p.y * 0.5 + 0.5));
    return out;
}

fn tileLoad(i: i32, pos: vec2<i32>) -> vec4<f32> {
    if (i == 0) { return textureLoad(src_tex0, pos, 0); }
    if (i == 1) { return textureLoad(src_tex1, pos, 0); }
    if (i == 2) { return textureLoad(src_tex2, pos, 0); }
    return textureLoad(src_tex3, pos, 0);
}

fn totalDimensions() -> vec2<u32> {
    let w = i32(transform.tiles_width);
    let h = i32(transform.tiles_height);
    if (w <= 0 || h <= 0) {
        return vec2<u32>(0u);
    }

    let dim0 = textureDimensions(src_tex0);
    var width = dim0.x;
    if (w > 1) { width += textureDimensions(src_tex1).x; }

    var height = dim0.y;
    if (h > 1) { height += textureDimensions(src_tex2).y; }

    return vec2<u32>(width, height);
}

fn totalLoad(pos: vec2<i32>) -> vec4<f32> {
    let ts = i32(transform.tile_size);
    var idx = (pos.y / ts) * 2 + (pos.x / ts);
    var pos0 = pos % ts;

    if (pos.x >= ts * 2 || pos.y >= ts * 2 || pos.x < 0 || pos.y < 0) {
        return vec4<f32>(0.0);
    }
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

fn textureSamplePoint(uv: vec2<f32>) -> vec4<f32> {
    let tex_size_u = totalDimensions();
    let tex_size = vec2<f32>(tex_size_u);
    let pixel_coord = uv * tex_size - 0.5;
    let base_coord = vec2<i32>(floor(pixel_coord));

    return totalLoad(vec2<i32>(base_coord.x, base_coord.y));
}

fn textureSampleCatmullRom(uv: vec2<f32>) -> vec4<f32> {
    let tex_size_u = totalDimensions();
    let tex_size = vec2<f32>(tex_size_u);
    let pixel_coord = uv * tex_size - 0.5;
    let base_coord = vec2<i32>(floor(pixel_coord));
    let f = fract(pixel_coord);

    let wx = catmull_rom_weights(f.x);
    let wy = catmull_rom_weights(f.y);
    let max_coord = vec2<i32>(tex_size_u) - 1;

    var final_color = vec4<f32>(0.0);
    for (var y: i32 = 0; y < 4; y++) {
        var row_color = vec4<f32>(0.0);
        let current_y = base_coord.y - 1 + y;
        for (var x: i32 = 0; x < 4; x++) {
            let current_x = base_coord.x - 1 + x;
            var texel = vec4<f32>(0.0);
            if (current_x >= 0 && current_x <= max_coord.x && current_y >= 0 && current_y <= max_coord.y) {
                texel = to_linear_exact(totalLoad(vec2<i32>(current_x, current_y)));
            }
            row_color += texel * wx[x];
        }
        final_color += row_color * wy[y];
    }

    return clamp(to_srgb_exact(final_color), vec4(0.0), vec4(1.0));
}

fn downsample(src_start: vec2<f32>, scale: vec2<f32>) -> vec4<f32> {
    let src_size_f = vec2<f32>(totalDimensions());
    let src_end = src_start + scale;

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
            let texel = to_linear_exact(totalLoad(vec2<i32>(x, y)));
            color_sum += texel * weight;
            weight_sum += weight;
        }
    }

    return to_srgb_exact(color_sum / max(weight_sum, 0.0001));
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let dst_size_f = vec2<f32>(transform.dst_width, transform.dst_height);
    let src_size_f = vec2<f32>(totalDimensions());

    let frag_coord = in.position.xy;

    let scale_factor = 1.0 / transform.scale;
    let scale_vec = vec2<f32>(scale_factor);
    let offset = transform.offset;

    if (scale_factor > 1.0) {
        let src_start = frag_coord * scale_vec - (offset * dst_size_f);
        return downsample(src_start, scale_vec);
    } else {
        let uv = (frag_coord * scale_vec - offset * dst_size_f) / src_size_f;
        return textureSampleCatmullRom(uv);
    }
}"""
}
