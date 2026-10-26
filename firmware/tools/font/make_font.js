#!/usr/bin/env node
'use strict';
//
// 生成固件用的中文字体子集，并逐码点校验覆盖率。
//
// 为什么不用 `npx lv_font_conv` 的命令行：GB2312 一级汉字 3755 个，作为 --symbols
// 传入约 11KB，超过 Windows cmd.exe 8191 字符的命令行上限；改用 --range 又要 2611 个
// 连续区段（约 36KB），同样超。这里改为在进程内调用同一个 CLI 的 run(argv)，参数以数组
// 传递，没有长度限制，语义与仓库命令行完全一致。
//
// 两份字体，各有用途：
//   campus_font_16 —— ASCII + GB2312 一级汉字 + 界面文案。动态待办标题靠它，
//                     所以必须覆盖整个一级字库，而不只是界面里出现过的字。
//   campus_font_20 —— 只有 ASCII + 界面文案。页眉与标题用它，字号大一点、bpp 高一点，
//                     贵不起来，因为字数少。
//
// 用法：
//   node tools/font/make_font.js --generate   # 生成字体
//   node tools/font/make_font.js --check      # 只校验，不写入

const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '..', '..');
const OUT_DIR = path.join(ROOT, 'main', 'fonts');
const FONT_SOURCE = process.env.CAMPUS_FONT_SOURCE
    || path.resolve(ROOT, '..', '..', 'ai-passport', 'assets', 'fonts',
                    'NotoSansSC-VariableFont_wght.ttf');

// 会渲染文本的源文件。新增 UI 文件必须加进来，否则新文案会漏字形。
const UI_SOURCES = ['main/campus_ui.c', 'main/campus_main.c'];

const FONTS = [
    { file: 'campus_font_16.c', size: 16, bpp: 1, full: true },
    { file: 'campus_font_20.c', size: 20, bpp: 2, full: false },
];

const ascii = [];
for (let c = 0x20; c <= 0x7e; c++) ascii.push(String.fromCharCode(c));

// GB2312 一级汉字：0xB0–0xD7 共 40 行 × 94 字，标准计数 3755 个。
//
// 注意：这 3760 个编码槽位里有 5 个是未分配。Node 的 gbk 解码器把它们映射到私用区
// （U+E810–U+E814），而字体里当然没有这些字形 —— 覆盖率检查就是在这里第一次报错的。
// 私用区不是真字符，必须排除，否则清单与字体永远对不上。
function gb2312Level1() {
    const decoder = new TextDecoder('gbk');
    const out = [];
    for (let hi = 0xb0; hi <= 0xd7; hi++) {
        for (let lo = 0xa1; lo <= 0xfe; lo++) {
            const ch = decoder.decode(new Uint8Array([hi, lo]));
            if (!ch || ch.length !== 1 || ch === '\uFFFD') continue;
            const cp = ch.codePointAt(0);
            if (cp >= 0xe000 && cp <= 0xf8ff) continue;  // 私用区
            out.push(ch);
        }
    }
    return out;
}

// 从 UI 源码里抽出字符串字面量，得到真实需要的文案码点（不靠人工列举，列了就会漂）。
function uiChars() {
    const set = new Set();
    for (const rel of UI_SOURCES) {
        const file = path.join(ROOT, rel);
        if (!fs.existsSync(file)) continue;
        for (const m of fs.readFileSync(file, 'utf8').matchAll(/"([^"\n]*)"/g)) {
            for (const ch of m[1]) set.add(ch);
        }
    }
    return [...set].sort();
}

function symbols(font, gb, ui) {
    const set = new Set(ascii);
    ui.forEach((ch) => set.add(ch));
    if (font.full) gb.forEach((ch) => set.add(ch));
    return [...set].join('');
}

function expectedCodepoints(font, gb, ui) {
    const set = new Set(ascii.map((ch) => ch.codePointAt(0)));
    ui.forEach((ch) => set.add(ch.codePointAt(0)));
    if (font.full) gb.forEach((ch) => set.add(ch.codePointAt(0)));
    return set;
}

// ---------------------------------------------------------------- 覆盖率解析
//
// 逐码点核对「生成物里到底有哪些字形」，而不是相信输入参数。
// lv_font_conv 对离散字符集用稀疏映射：cmaps[] 的 range_start 只是最小值，
// 真正的码点靠 unicode_list 里的相对偏移给出。把 range_length 当连续区间会得出
// 「覆盖了 U+4E00..U+9FA5」这种假结论。

function parseCovered(source) {
    const cmapsMatch = source.match(/lv_font_fmt_txt_cmap_t\s+cmaps\[\]\s*=\s*\{([\s\S]*?)\n\};/);
    if (!cmapsMatch) {
        throw new Error('解析不到 cmaps[]，生成格式变了吗？');
    }

    const arrays = new Map();
    for (const m of source.matchAll(/static const uint16_t (\w+)\[\]\s*=\s*\{([\s\S]*?)\};/g)) {
        arrays.set(m[1], [...m[2].matchAll(/0x([0-9a-fA-F]+)/g)].map((x) => parseInt(x[1], 16)));
    }

    const covered = new Set();
    let entries = 0;
    for (const block of cmapsMatch[1].split('}')) {
        const start = block.match(/\.range_start\s*=\s*(0x[0-9a-fA-F]+|\d+)/);
        if (!start) continue;
        entries++;
        const length = block.match(/\.range_length\s*=\s*(\d+)/);
        const list = block.match(/\.unicode_list\s*=\s*(\w+)/);
        const listName = list && list[1] !== 'NULL' ? list[1] : null;

        if (listName === null) {
            const from = Number(start[1]);
            const count = length ? Number(length[1]) : 0;
            for (let i = 0; i < count; i++) covered.add(from + i);
            continue;
        }
        const offsets = arrays.get(listName);
        if (!offsets) {
            throw new Error(`找不到码点表 ${listName}`);
        }
        const from = Number(start[1]);
        offsets.forEach((offset) => covered.add(from + offset));
    }
    if (entries === 0) {
        throw new Error('cmaps[] 里一个条目都没解析到');
    }
    return covered;
}

function check(font, expected) {
    const file = path.join(OUT_DIR, font.file);
    if (!fs.existsSync(file)) {
        throw new Error(`缺少生成字体 ${font.file}；先跑 --generate`);
    }
    const covered = parseCovered(fs.readFileSync(file, 'utf8'));

    const missing = [...expected].filter((cp) => !covered.has(cp));
    // 生成器会把 ASCII 空白与常见标点一起带上，这些不算异常；其余多余字形说明
    // 字体与码点清单已经不同步（上一次生成的残留）。
    const allowedExtra = new Set([0x20, 0xa0, 0x3000, 0xfffd]);
    const extra = [...covered].filter((cp) => !expected.has(cp) && !allowedExtra.has(cp));

    const hex = (list) => list.slice(0, 12).map((cp) => 'U+' + cp.toString(16).toUpperCase().padStart(4, '0')).join(' ');

    if (missing.length > 0) {
        throw new Error(`${font.file} 缺少 ${missing.length} 个码点: ${hex(missing)}`);
    }
    if (extra.length > 0) {
        throw new Error(`${font.file} 含有 ${extra.length} 个清单外字形: ${hex(extra)}；字体与码点清单不同步，请重新生成`);
    }
    console.log(`[font] ${font.file}: ${covered.size} 个字形，与清单逐码点一致`);
}

// ---------------------------------------------------------------- 主流程

async function main() {
    const generate = process.argv.includes('--generate');
    const gb = gb2312Level1();
    const ui = uiChars();
    const uiNonAscii = ui.filter((ch) => ch.codePointAt(0) > 0x7e);

    console.log(`[font] GB2312 一级汉字 ${gb.length} 个；界面文案非 ASCII 字符 ${uiNonAscii.length} 个`);
    console.log(`[font] 字体源 ${FONT_SOURCE}`);
    if (!fs.existsSync(FONT_SOURCE)) {
        throw new Error('找不到字体源 TTF');
    }

    fs.mkdirSync(OUT_DIR, { recursive: true });

    if (generate) {
        const cli = require('lv_font_conv/lib/cli');
        for (const font of FONTS) {
            const out = path.join(OUT_DIR, font.file);
            const chars = symbols(font, gb, ui);
            if (fs.existsSync(out)) fs.unlinkSync(out);
            process.stdout.write(`[font] 生成 ${font.file}（${font.size}px ${font.bpp}bpp，${chars.length} 个字符）… `);
            await cli.run([
                '--font', FONT_SOURCE,
                '--size', String(font.size),
                '--bpp', String(font.bpp),
                '--format', 'lvgl',
                '--no-compress',
                // 必须给：否则生成物的 #else 分支写的是 "lvgl/lvgl.h"，
                // 而 ESP-IDF 的 lvgl 组件只在 include 根目录下提供 "lvgl.h"，
                // 于是编译直接 fatal error。仓库的字体也是靠这个参数。
                '--lv-include', 'lvgl.h',
                '--lv-font-name', path.basename(font.file, '.c'),
                '--symbols', chars,
                '-o', out,
            ]);
            console.log(`${(fs.statSync(out).size / 1024).toFixed(0)} KB`);
        }
    }

    for (const font of FONTS) {
        check(font, expectedCodepoints(font, gb, ui));
    }
    console.log('[font] 覆盖率检查通过');
}

main().catch((err) => {
    console.error('[font] 失败: ' + err.message);
    process.exit(1);
});
