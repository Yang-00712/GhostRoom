export async function onRequest({ request, env }) {
  const url = new URL(request.url);
  const room = url.searchParams.get("room") || "";
  if (!/^[0-9a-f]{32}$/.test(room)) return new Response("bad room", { status: 400 });
  if (request.headers.get("Upgrade") !== "websocket") return new Response("websocket only", { status: 426 });
  return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
}
