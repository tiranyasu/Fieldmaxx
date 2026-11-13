package com.fieldmaxx.server.release

import com.fieldmaxx.core.model.AppRelease

private fun esc(s: String) = buildString {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
        else -> append(c)
    }
}

/** The phone-friendly install page (spec 6.11): one big button and three steps. */
fun downloadPage(release: AppRelease?): String {
    val action = if (release == null) {
        """<p class="none">No version published yet. Check back soon.</p>"""
    } else {
        """<a class="button" href="/app/fieldmaxx-${esc(release.versionName)}.apk">Download Fieldmaxx for Android</a>
    <p class="version">Version ${esc(release.versionName)}</p>"""
    }
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fieldmaxx for Android</title>
<style>
  body { margin: 0; font-family: system-ui, sans-serif; background: #f8fafc; color: #0f172a; }
  header { background: #0f172a; color: #fff; padding: 18px 16px; font-size: 1.3rem; font-weight: 600; }
  main { max-width: 480px; margin: 0 auto; padding: 24px 16px; }
  .button { display: block; box-sizing: border-box; width: 100%; padding: 20px 16px; background: #2563eb; color: #fff; text-align: center; text-decoration: none; font-size: 1.25rem; font-weight: 600; border-radius: 12px; }
  .version { text-align: center; color: #475569; }
  .none { text-align: center; font-size: 1.1rem; padding: 20px 0; }
  ol { padding-left: 1.4rem; font-size: 1.05rem; line-height: 1.5; }
  li { margin-bottom: 12px; }
  h2 { font-size: 1.1rem; margin-top: 32px; }
</style>
</head>
<body>
<header>Fieldmaxx</header>
<main>
    $action
    <h2>How to install</h2>
    <ol>
      <li>Allow install from this source once. Android will ask the first time; tap Settings and switch on Allow from this source.</li>
      <li>Open the downloaded file and tap Install.</li>
      <li>Open Fieldmaxx and sign in with the username and password you were given.</li>
    </ol>
</main>
</body>
</html>
"""
}
