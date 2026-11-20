self.addEventListener('push', event => {
    let payload;
    try {
        payload = event.data ? event.data.json() : null;
    } catch (error) {
        return;
    }
    if (!payload || payload.category !== 'NOTIFICATION' ||
        !Number.isSafeInteger(payload.eventId) || payload.eventId <= 0 ||
        Object.keys(payload).sort().join(',') !== 'category,eventId') {
        return;
    }
    event.waitUntil(self.registration.showNotification('Новое событие', {
        body: 'ERP',
        tag: 'erp-notification-' + payload.eventId,
        data: { eventId: payload.eventId }
    }));
});

self.addEventListener('notificationclick', event => {
    event.notification.close();
    const eventId = event.notification.data && event.notification.data.eventId;
    if (!Number.isSafeInteger(eventId) || eventId <= 0) return;

    event.waitUntil((async () => {
        const windows = await clients.matchAll({ type: 'window', includeUncontrolled: true });
        const client = windows.find(candidate => candidate.url.startsWith(self.location.origin));
        if (client) {
            await client.focus();
            client.postMessage({ type: 'OPEN_NOTIFICATION', eventId: eventId });
            return;
        }
        await clients.openWindow(self.location.origin + '/#notification=' + eventId);
    })());
});
