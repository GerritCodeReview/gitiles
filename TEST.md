# Testing gitiles markdown rendering.

There seems to be a conflict with nested lists with paragraph text and automatic
"8 space indent" code block detection.

* Zero indent

    Text for first level list item (indent: 4)

    * Four indent

        Text for second level list item (indent: 8)

        * Eight indent

            Text for third level list item (indent: 12)

            * Twelve indent

                Text for fourth level list item (indent: 16)

* Another Zero indent, same list

    More text for first level list item (indent: 4)

    * Another Four indent, same list

        More text for second level list item (indent: 8)
