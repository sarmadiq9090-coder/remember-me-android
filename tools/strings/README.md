One file per feature area. Each defines `S = [("key", "Arabic", "English"), ...]` and optionally
`P = {"key": ({arabic CLDR forms}, {english forms})}`. `python tools/strings_gen.py app/src/main/res`
merges them with the main table; a duplicate key stops the build.
