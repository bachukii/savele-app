// შიდა აზომვის ესკიზი — ოფლაინ ქეში. ახალი ვერსიისას VERSION შეცვალეთ.
const VERSION = "eskizi-v227";
const CORE = ["./", "./index.html", "./manifest.webmanifest", "./icon.svg", "./icon-192.png", "./icon-512.png"];
self.addEventListener("install", e => { e.waitUntil(caches.open(VERSION).then(c => c.addAll(CORE)).then(() => self.skipWaiting())); });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  if (url.hostname.endsWith("napr.gov.ge")) return;   // ორთოფოტო / საკადასტრო — ქეშის გარეშე, პირდაპირ ინტერნეტიდან
  if (/(youtube|ytimg|ggpht|googlevideo|youtu\.be)/.test(url.hostname)) return;   // ვიდეო — ქეშის გარეშე
  if (url.hostname.endsWith("openstreetmap.org")) return;   // რუკის ფილები — ქეშის გარეშე
  if (url.hostname.endsWith("supabase.co") || url.pathname.includes("/rest/v1/") || url.pathname.includes("/auth/v1/")) return;   // ანგარიში და ღრუბელი — ყოველთვის ინტერნეტიდან
  // გვერდი: ჯერ ინტერნეტი (განახლებისთვის), ვერ მოხერხდა — ქეში
  if (url.origin === location.origin && (e.request.mode === "navigate" || url.pathname.endsWith(".html"))) {
    e.respondWith(fetch(e.request).then(r => { const cp = r.clone(); caches.open(VERSION).then(c => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request).then(r => r || caches.match("./index.html"))));
    return;
  }
  // დანარჩენი (ხატულები, შრიფტები): ჯერ ქეში
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request).then(res => { const cp = res.clone(); caches.open(VERSION).then(c => c.put(e.request, cp)); return res; }).catch(() => r)));
});
