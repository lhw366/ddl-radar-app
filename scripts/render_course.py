"""Render the deliberately small Markdown subset used by this handout, offline."""
from pathlib import Path
import html
import re

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'docs/git-github-course.md'
target = source.with_suffix('.html')

def inline(text):
    tokens = []
    def protect(match):
        tokens.append('<code>' + html.escape(match.group(1)) + '</code>')
        return f'\x00{len(tokens)-1}\x00'
    text = re.sub(r'`([^`]+)`', protect, text)
    text = html.escape(text)
    text = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'\[([^\]]+)\]\((https?://[^)]+)\)', r'<a href="\2" rel="noopener">\1</a>', text)
    for index, token in enumerate(tokens):
        text = text.replace(f'\x00{index}\x00', token)
    return text

lines = source.read_text(encoding='utf-8').splitlines()
output, toc = [], []
i = 0
while i < len(lines):
    line = lines[i]
    if not line.strip(): i += 1; continue
    if line.startswith('```'):
        language = line[3:].strip()
        code = []
        i += 1
        while i < len(lines) and not lines[i].startswith('```'):
            code.append(lines[i]); i += 1
        output.append('<div class="code-label">' + html.escape(language or '示意') + '</div><pre><code>'
                      + html.escape('\n'.join(code)) + '</code></pre>')
        i += 1; continue
    if line.startswith('|'):
        rows = []
        while i < len(lines) and lines[i].startswith('|'):
            fields = [cell.strip() for cell in lines[i].strip('|').split('|')]
            if not all(re.fullmatch(r'[:\-\s]+', cell) for cell in fields): rows.append(fields)
            i += 1
        output.append('<div class="table-wrap"><table><thead><tr>' + ''.join('<th>'+inline(c)+'</th>' for c in rows[0]) + '</tr></thead><tbody>')
        output.extend('<tr>'+''.join('<td>'+inline(c)+'</td>' for c in row)+'</tr>' for row in rows[1:])
        output.append('</tbody></table></div>'); continue
    heading = re.match(r'^(#{1,3}) (.*)$', line)
    if heading:
        level, title = len(heading[1]), heading[2]
        anchor = f'section-{len(toc)}' if level > 1 else 'title'
        if level > 1: toc.append((anchor, title))
        output.append(f'<h{level} id="{anchor}">{inline(title)}</h{level}>')
        i += 1; continue
    if line.startswith('- '):
        output.append('<ul>')
        while i < len(lines) and lines[i].startswith('- '):
            output.append('<li>'+inline(lines[i][2:])+'</li>'); i += 1
        output.append('</ul>'); continue
    paragraph = [line]
    i += 1
    while i < len(lines) and lines[i].strip() and not re.match(r'^(#|```|\||- )', lines[i]):
        paragraph.append(lines[i]); i += 1
    output.append('<p>'+inline(' '.join(paragraph))+'</p>')

navigation = ''.join(f'<a href="#{anchor}">{html.escape(title)}</a>' for anchor,title in toc)
page = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Git 与 GitHub：从入门到进阶实战 · 学生讲义</title>
<style>
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}
body{margin:0;background:#f5f3ef;color:#202124;font-family:"Microsoft YaHei","PingFang SC",system-ui,sans-serif;font-size:17px;line-height:1.85}
nav{position:fixed;inset:0 auto 0 0;width:282px;padding:28px 22px;overflow:auto;background:#eae7df;border-right:1px solid #d7d2c8}
nav strong{display:block;color:#000;font-size:18px;margin-bottom:16px}nav a{display:block;color:#303637;text-decoration:none;font-size:13px;line-height:1.55;padding:7px 0;border-bottom:1px solid #d8d4ca}
main{margin:30px 36px 72px 318px;background:white;max-width:1000px;padding:46px 54px;box-shadow:0 12px 40px #23232209}
h1,h2,h3{color:#000;line-height:1.45;letter-spacing:.02em}h1{font-size:34px;margin:0 0 20px}h2{font-size:25px;margin:54px 0 22px;border-top:1px solid #d9d9d9;padding-top:28px}p{margin:0 0 19px}strong{color:#111}a{color:#185c72;text-underline-offset:3px}
code{font-family:Consolas,"Cascadia Mono",monospace;font-size:.86em;background:#f2f3f4;padding:2px 4px;border-radius:3px;overflow-wrap:anywhere}
pre{background:#f3f4f5;border:1px solid #dedfe0;border-radius:5px;padding:17px 20px;margin:0 0 23px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px}pre code{background:none;padding:0;font-size:inherit}
.code-label{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#5a6267;margin:18px 0 5px}.table-wrap{overflow:auto;margin:24px 0}table{width:100%;border-collapse:collapse;font-size:14px;line-height:1.7}th,td{border:1px solid #d9d9d9;padding:11px 13px;vertical-align:middle;text-align:left}th{background:#e5edf1;color:#000}tr:nth-child(even){background:#f7f9fa}ul{padding-left:24px;margin-bottom:24px}
.toolbar{display:flex;gap:10px;margin-bottom:28px;align-items:center;font-size:12px;color:#5b6165}button{border:1px solid #bbc6ca;background:#fff;color:#202124;padding:7px 14px;border-radius:5px;cursor:pointer}footer{border-top:1px solid #ddd;padding-top:20px;margin-top:50px;color:#616567;font-size:13px}
@media(max-width:1100px){nav{width:232px;padding:20px 16px}main{margin-left:252px;margin-right:20px;padding:32px}}@media(max-width:760px){nav{position:static;width:100%;max-height:210px;border-right:0;border-bottom:1px solid #ddd}main{margin:14px;padding:24px 20px}h1{font-size:28px}h2{font-size:22px}body{font-size:16px}}
@media print{@page{size:A4;margin:18mm}body{background:#fff;font-size:10.5pt;line-height:1.65}nav,.toolbar{display:none}main{margin:0;padding:0;max-width:none;box-shadow:none}h1{font-size:24pt}h2{font-size:17pt;margin:26pt 0 12pt;padding-top:16pt;break-after:avoid}p{orphans:3;widows:3;margin-bottom:10pt}pre{font-size:8.5pt;line-height:1.45;padding:9pt;break-inside:avoid}table{font-size:9pt}thead{display:table-header-group}tr{break-inside:avoid}th,td{padding:7pt}a{color:#000}footer{font-size:8pt}}
</style></head><body><nav aria-label="讲义目录"><strong>Git / GitHub 实战课堂</strong>''' + navigation + '''</nav><main>
<div class="toolbar"><span>离线讲义 · 12 课 + 教师指导</span><button onclick="window.print()">打印 / 保存为 PDF</button></div>
''' + '\n'.join(output) + '''<footer>本文件可离线阅读；目录可点击。源讲义与项目文档采用 Git 维护。打印时可在浏览器选择保存为 PDF。</footer></main></body></html>'''
target.write_text(page, encoding='utf-8')
assert page.count('<h2 ') == len(toc)
assert '<script src=' not in page and '<link ' not in page
assert len(re.findall(r'```', source.read_text(encoding='utf-8'))) % 2 == 0
print(f'Created {target.name}: {len(toc)} sections, {len(page)} characters, offline assets only')
