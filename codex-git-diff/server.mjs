import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';

const cwd = process.argv[2] || process.cwd();
function git(args) {
  return execFileSync('git', ['--no-optional-locks', ...args], {
    cwd, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: 10000,
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
  });
}
git(['rev-parse', '--show-toplevel']);
const token = '/' + randomBytes(18).toString('hex');
const html = readFileSync(new URL('./viewer.html', import.meta.url));
const groups = { staged: ['--cached'], unstaged: [], untracked: null };
function list(group) {
  const args = groups[group];
  if (!args) return git(['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean).map(path => ({path, status:'?'}));
  const fields = git(['diff', ...args, '--no-renames', '--name-status', '-z', '--']).split('\0');
  const stats = new Map(git(['diff', ...args, '--no-renames', '--numstat', '-z', '--']).split('\0').filter(Boolean).map(line => {
    const match = /^([^\t]+)\t([^\t]+)\t([\s\S]+)$/.exec(line);
    return [match[3], [match[1],match[2]]];
  }));
  const entries = [];
  for (let i = 0; i + 1 < fields.length; i += 2) entries.push({status:fields[i], path:fields[i+1], counts:stats.get(fields[i+1])});
  return entries;
}
function patch(group, path) {
  const flags = ['--no-ext-diff','--no-textconv','--no-color','--no-renames','--unified=3'];
  if (group === 'untracked') {
    try { return git(['diff','--no-index', ...flags, '--', '/dev/null', './' + path]); }
    catch (error) { if (error.status === 1) return error.stdout; throw error; }
  }
  return git(['--literal-pathspecs','diff', ...groups[group], ...flags,'--', path]);
}
const server = createServer((req,res) => {
  res.setHeader('Cache-Control','no-store');
  res.setHeader('Referrer-Policy','no-referrer');
  res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method !== 'GET' || req.headers.host !== '127.0.0.1:' + server.address().port) { res.writeHead(403).end(); return; }
    if (url.pathname === token + '/') { res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(html); return; }
    const send = data => res.writeHead(200,{'Content-Type':'application/json; charset=utf-8'}).end(JSON.stringify(data));
    if (url.pathname === token + '/changes') { send(Object.fromEntries(Object.keys(groups).map(g => [g,list(g)]))); return; }
    if (url.pathname === token + '/diff') {
      const group = url.searchParams.get('group'), path = url.searchParams.get('path');
      if (Object.hasOwn(groups,group) && list(group).some(entry => entry.path === path)) { send({patch:patch(group,path)}); return; }
    }
    res.writeHead(404).end();
  } catch { res.writeHead(500).end('Unable to read Git changes.'); }
});
server.listen(Number(process.env.GIT_DIFF_PORT || 0),'127.0.0.1', () => console.log('http://127.0.0.1:' + server.address().port + token + '/'));
