import re, os

f = 'E:\\vscode\\dbboys\\src\\com\\dbboys\\dialect\\dameng\\DamengDialect.java'
with open(f, 'r', encoding='utf-8') as fh:
    lines = fh.readlines()

new_lines = []
for line in lines:
    if re.search(r'v\$database df on 1=1', line): continue
    if re.search(r'cross join \(select page/1024', line): continue
    if 'left join v$datafile d on t.id=d.group_id' in line:
        line = line.replace(
            'left join v$datafile d on t.id=d.group_id',
            'left join (select group_id, sum(bytes) total_bytes from v$datafile group by group_id) d on t.id=d.group_id'
        )
    if 'd.bytes' in line and 'd.group_id' not in line:
        line = line.replace('d.bytes', 'd.total_bytes')
    new_lines.append(line)

with open(f, 'w', encoding='utf-8') as fh:
    fh.writelines(new_lines)

with open(f, 'r', encoding='utf-8') as fh:
    lines2 = fh.readlines()
for i in range(626, min(640, len(lines2))):
    print(f'{i+1}: {lines2[i].rstrip()}')