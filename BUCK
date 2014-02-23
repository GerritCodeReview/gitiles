genrule(
  name = 'gitiles',
  cmd = 'echo done >$OUT',
  deps = [':servlet', ':war'],
  out = 'gitiles.out',
)

genrule(
  name = 'servlet',
  cmd = 'ln -s $(location //gitiles-servlet:servlet) $OUT',
  deps = ['//gitiles-servlet:servlet'],
  out = 'gitiles-servlet.jar'
)

genrule(
  name = 'war',
  cmd = 'ln -s $(location //gitiles-war:gitiles) $OUT',
  deps = ['//gitiles-war:gitiles'],
  out = 'gitiles.war'
)
