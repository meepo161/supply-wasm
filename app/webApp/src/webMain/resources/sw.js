// NTF-09: Service Worker для фоновых уведомлений ERP Авиаагрегат-Н
self.addEventListener('install', (event) => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
    const data = event.data ? event.data.json() : { title: 'ERP Авиаагрегат-Н', body: 'Новое событие' };
    event.waitUntil(
        self.registration.showNotification(data.title || 'ERP Авиаагрегат-Н', {
            body: data.body || 'Новое событие в системе'
        })
    );
});
