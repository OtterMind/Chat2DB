import io
with io.open('large_1m.sql', 'w', encoding='utf-8') as f:
    for i in range(1_000_000):
        f.write(f"INSERT INTO import004_items (name, amount) VALUES ('row{i}', {i}.00);\n")
