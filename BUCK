DEPS = [
  '//gitiles-servlet:servlet',
  '//gitiles-servlet:src',
  '//gitiles-servlet:javadoc',
  '//gitiles-war:gitiles',
]

def go():
  a = []
  for d in DEPS:
    n,t = d.split(':')
    a.append(':' + t)
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
    deps = a,
    out = '__fake.gitiles__',
  )

go()
