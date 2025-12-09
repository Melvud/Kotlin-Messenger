# SSL Certificate Generation Instructions

1. Install Certbot and Nginx plugin:
   ```bash
   sudo apt update
   sudo apt install certbot python3-certbot-nginx
   ```

2. Obtain SSL certificate:
   ```bash
   sudo certbot --nginx -d ivsilant.space
   ```

3. Certbot will automatically update your Nginx configuration to enable HTTPS.
4. Verify auto-renewal:
   ```bash
   sudo certbot renew --dry-run
   ```
