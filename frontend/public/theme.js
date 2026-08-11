// Paints the stored theme before first render, so the console never flashes the wrong ground.
//
// A file rather than an inline <script>: the console's Content-Security-Policy is `script-src
// 'self'` with no `unsafe-inline` and no nonce, so an inline block here is silently blocked in
// production and the flash it exists to prevent happens on every load. Same origin, one tiny
// request in the head, and the policy stays as strict as it reads.
try {
  var stored = localStorage.getItem('janus.theme');
  document.documentElement.dataset.theme = stored === 'light' ? 'light' : 'dark';
  if (stored === 'light') {
    document.querySelector('meta[name="theme-color"]').setAttribute('content', '#f4f5f8');
  }
} catch (e) {
  /* storage blocked: the dark default in the markup already applies */
}
