const https = require('https');

const token = process.env.GH_TOKEN;
const opts = {
  hostname: 'api.github.com',
  path: '/user',
  method: 'GET',
  headers: {
    'Authorization': `token ${token}`,
    'User-Agent': 'livingdocs-backend',
    'Accept': 'application/vnd.github+json',
  },
};
const req = https.request(opts, (res) => {
  let data = '';
  res.on('data', (c) => data += c);
  res.on('end', () => {
    console.log(data);
  });
});
req.on('error', (e) => console.error(e));
req.end();
