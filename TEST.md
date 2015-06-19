# Testing gitiles markdown rendering

There seems to be a conflict with nested lists containing paragraph text and automatic
"8 space indent" code block detection.

* First level list item (indent: 0)

    Text for first level list item (indent: 4)

    * Second level list item (indent: 4)

        Text for second level list item (indent: 8)

        * Third level list item (indent: 8)

            Text for third level list item (indent: 12)

            * Fourth level list item (indent: 12)

                Text for fourth level list item (indent: 16)

* Another First level list item (indent: 0)

    More text for first level list item (indent: 4)

    * Another Second level indent (indent: 4)

        More text for second level list item (indent: 8)


Maybe I'm doing it wrong, but I can't figure out any other way to either:

1. Generate nested lists with text, or
2. Prevent the automatic "8 space" code block formatting.
