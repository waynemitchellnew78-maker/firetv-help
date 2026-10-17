FROM node:22-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY server.js ./
COPY public ./public
ENV PORT=8790
EXPOSE 8790
CMD ["node", "server.js"]
