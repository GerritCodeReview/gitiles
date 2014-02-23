DEPS = [
  '//gitiles-servlet:servlet',
  '//gitiles-servlet:src',
  '//gitiles-servlet:javadoc',
  '//gitiles-war:gitiles',
]

java_library(
  name = 'classpath',
  deps = [
    '//gitiles-servlet:servlet',
    '//gitiles-dev:dev',
  ]
)

def go():
  a = set()
  for d in DEPS:
    n,t = d.split(':')
    a.add(t)
    out = "%s.%s" % (t, 'war' if 'war' in n else 'jar')
    genrule(
      name = t,
      cmd = 'ln -s $(location %s) $OUT' % d,
      deps = [d],
      out = out,
    )

  genrule(
    name = 'all',
    cmd = 'echo done >$OUT',
    deps = [':' + e for e in a],
    out = '__fake.gitiles__',
  )

go()
