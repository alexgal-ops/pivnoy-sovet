// Пивной совет — рассылка пуш-уведомлений (веб и айфон).
// Вызывается триггером базы на каждую запись в public.docs ({id}) и самим приложением:
//   GET  ?vapid=1                              -> публичный VAPID-ключ
//   POST {action:'subscribe', member, sub}     -> сохранить подписку устройства
//   POST {action:'unsubscribe', endpoint}      -> удалить подписку
// Ключи VAPID создаются один раз и хранятся в таблице push_keys (без доступа снаружи).
import webpush from "npm:web-push@3.6.7";
import { createClient } from "npm:@supabase/supabase-js@2.116.0";

function serverKey(): string {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  try { const v = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); return String(Object.values(v)[0] || ""); } catch { return ""; }
}
const sb = createClient(Deno.env.get("SUPABASE_URL")!, serverKey());
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type, authorization, apikey", "Access-Control-Allow-Methods": "GET, POST, OPTIONS" };
const WD = ["Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"];
const MON = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
const FRESH_MS = 15 * 60 * 1000;

const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { ...CORS, "Content-Type": "application/json" } });

async function keys() {
  const got = await sb.from("push_keys").select("pub, priv").eq("id", 1).maybeSingle();
  if (got.data) return got.data;
  const k = webpush.generateVAPIDKeys();
  await sb.from("push_keys").insert({ id: 1, pub: k.publicKey, priv: k.privateKey });
  const again = await sb.from("push_keys").select("pub, priv").eq("id", 1).single();
  return again.data!;
}
async function doc(id: string) {
  const r = await sb.from("docs").select("data").eq("id", id).maybeSingle();
  return r.data ? r.data.data : null;
}
async function members() {
  const r = await sb.from("docs").select("id, data").like("id", "members/%");
  const m: Record<string, any> = {};
  (r.data || []).forEach((x: any) => { m[x.id.slice(8)] = x.data; });
  return m;
}
async function once(ev: string) {
  const r = await sb.from("push_sent").insert({ event: ev });
  return !r.error; // primary-key conflict = already sent
}
const fresh = (t: unknown) => typeof t === "number" && Date.now() - t < FRESH_MS;
function when(p: any) {
  try { const [y, mo, d] = String(p.date).split("-").map(Number); const dt = new Date(Date.UTC(y, mo - 1, d)); return `${WD[dt.getUTCDay()]}, ${d} ${MON[mo - 1]} · ${p.time}`; }
  catch { return `${p.date} ${p.time}`; }
}
function place(pl: any, m: Record<string, any>) {
  if (!pl) return "";
  if (pl.type === "bar") return pl.name ? `Бар «${pl.name}»` : "Бар";
  if (pl.type === "online") return "Онлайн";
  const x = m[pl.type] || {};
  return x.gen ? `У ${x.gen}` : `Хата: ${x.name || "кто-то"}`;
}
async function send(to: string[], payload: Record<string, string>) {
  if (!to.length) return 0;
  const k = await keys();
  webpush.setVapidDetails("https://alexgal-ops.github.io/pivnoy-sovet/", k.pub, k.priv);
  const subs = await sb.from("push_subs").select("endpoint, sub").in("member", to);
  let sent = 0;
  for (const s of subs.data || []) {
    try { await webpush.sendNotification(s.sub, JSON.stringify(payload)); sent++; }
    catch (e: any) { if (e && (e.statusCode === 404 || e.statusCode === 410)) await sb.from("push_subs").delete().eq("endpoint", s.endpoint); else console.log("push error", e && e.statusCode); }
  }
  return sent;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const url = new URL(req.url);
  if (url.searchParams.get("vapid")) return json({ key: (await keys()).pub });

  let body: any = {};
  try { body = await req.json(); } catch { /* empty */ }

  if (body.action === "subscribe") {
    const sub = body.sub, member = String(body.member || "");
    if (!sub || !sub.endpoint || !member) return json({ error: "bad subscription" }, 400);
    await sb.from("push_subs").upsert({ endpoint: sub.endpoint, member, sub, updated_at: new Date().toISOString() });
    return json({ ok: true });
  }
  if (body.action === "unsubscribe") {
    if (body.endpoint) await sb.from("push_subs").delete().eq("endpoint", String(body.endpoint));
    return json({ ok: true });
  }

  // Database trigger: {id: "<collection>/<key>"}. The payload is only a pointer — the row is re-read here.
  const id = String(body.id || "");
  const i = id.indexOf("/");
  if (i < 0) return json({ skip: "no id" });
  const coll = id.slice(0, i), key = id.slice(i + 1);
  const d = await doc(id);
  if (!d) return json({ skip: "gone" });
  const m = await members();
  const name = (x: string) => (m[x] && m[x].name) || "Кто-то";
  const all = Object.keys(m).filter((x) => !m[x].left);
  const out: number[] = [];

  if (coll === "proposals") {
    if (fresh(d.at) && await once("new:" + key))
      out.push(await send(all.filter((x) => x !== d.by), { title: `${name(d.by)} предложил сходку`, body: `Какой твой ответ, май френд? ${when(d)} · ${place(d.place, m)}`, tag: "p-" + key }));
    if (d.confirmed && fresh(d.confirmedAt) && await once("conf:" + key)) {
      const ans = await sb.from("docs").select("id, data").like("id", `answers/${key}__%`);
      const inn = (ans.data || []).filter((r: any) => r.data && r.data.in === true).map((r: any) => r.id.split("__")[1]);
      out.push(await send(inn.filter((x: string) => x !== d.by), { title: "Сходка в силе", body: `${when(d)} · ${place(d.place, m)} · ${inn.length} в пиве`, tag: "c-" + key }));
    }
  } else if (coll === "answers") {
    const [pid, who] = key.split("__");
    const p = await doc("proposals/" + pid);
    if (p && p.by !== who && fresh(d.at) && await once(`ans:${key}:${d.in === true ? "in" : "out"}`))
      out.push(await send([p.by], { title: `${name(who)} ${d.in === true ? "в пиве" : "пас"}`, body: `${when(p)} · ${place(p.place, m)}`, tag: "a-" + pid }));
  } else if (coll === "members") {
    if (fresh(d.joinedAt) && await once("mem:" + key))
      out.push(await send(all.filter((x) => x !== key), { title: `${name(key)} вступил в тусу`, body: "Пивной совет стал больше", tag: "m-" + key }));
  }
  return json({ ok: true, out });
});
