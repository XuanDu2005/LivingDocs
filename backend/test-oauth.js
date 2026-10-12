const https = require('https');

const data = JSON.stringify({
  client_id: 'Ov23liHgJFSFryoJ34rS',
  client_secret: '57b32651b039d50e43990f28931742f1796679d9',
  code: 'test_invalid_code',
  redirect_uri: 'http://localhost:8080/api/v1/github/oauth/callback',
});

const opts = {
  hostname: 'github.com',
  path: '/login/oauth/access_token',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'Content-Length': data.length,
    'User-Agent': 'livingdocs-backend',
  },
};

const req = https.request(opts, (res) => {
  let body = '';
  res.on('data', (c) => body += c);
  res.on('end', () => console.log('Status:', res.statusCode, '\nBody:', body));
});
req.on('error', (e) => console.error('Error:', e));
req.write(data);
req.end();
