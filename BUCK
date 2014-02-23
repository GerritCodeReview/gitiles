genrule(
  name = 'gitiles',
  cmd = 'echo done >$OUT',
  deps = [':servlet', ':src', ':doc', ':war'],
  out = 'gitiles.out',
)

genrule(
  name = 'servlet',
  cmd = 'ln -s $(location //gitiles-servlet:servlet) $OUT',
  deps = ['//gitiles-servlet:servlet'],
  out = 'gitiles-servlet.jar'
)

genrule(
  name = 'src',
  cmd = 'ln -s $(location //gitiles-servlet:src) $OUT',
  deps = ['//gitiles-servlet:src'],
  out = 'gitiles-src.jar'
)

genrule(
  name = 'doc',
  cmd = 'ln -s $(location //gitiles-servlet:javadoc) $OUT',
  deps = ['//gitiles-servlet:javadoc'],
  out = 'gitiles-javadoc.jar'
)

genrule(
  name = 'war',
  cmd = 'ln -s $(location //gitiles-war:gitiles) $OUT',
  deps = ['//gitiles-war:gitiles'],
  out = 'gitiles.war'
)
