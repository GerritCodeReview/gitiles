#!/usr/bin/python
#
# Generate an HTML document to generate a map of MIME types to modes.
# Usage: gen_mode_map.py </path/to/codemirror-dir>
# Open out.html in a browser and paste the results as Java.

import os
import sys

cm_dir = sys.argv[1]

exclude = set([
  'gfm.js', # Requires overlay addon, not worth the effort of loading it.
])
paths = [os.path.join(cm_dir, 'lib/codemirror.js')]
for dirpath, dirnames, filenames in os.walk(os.path.join(cm_dir, 'mode')):
  for f in filenames:
    if f.endswith('.js') and not f.endswith('test.js') and f not in exclude:
      paths.append(os.path.join(dirpath, f))

print r"""
<html>
<head>
"""

for p in paths:
  print '<script src="file://%s"></script>' % os.path.abspath(p)

print r"""
  <title>node_map</title>
</head>

<body>
<script>
document.write('<pre>');
document.write('  private static final ImmutableMap&lt;String, String&gt; BY_MIME_TYPE =\n')
document.write('      ImmutableMap.builder()\n')
for (var k in CodeMirror.mimeModes) {
  var v = CodeMirror.mimeModes[k];
  if (typeof v != "string") {
    v = v.name;
  }
  if (v != "null") {
    document.write('        .put("' + k + '", "' + v + '")\n');
  }
}
document.write('        .build();\n');
document.write('</pre>');
</script>
</body>
</html>
"""
