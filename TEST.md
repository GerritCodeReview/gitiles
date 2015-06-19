# Testing gitiles markdown rendering.

There seems to be a conflich with heavily nested lists (or moderately nested
lists with paragraph text) and automatic "8 space indent" code block detection.

* Zero indent
  * Two indent
    * Four indent
      * Six indent
        * Eight indent
          * Ten indent
* Another Zero indent, same list
  * Another Two indent, same list
