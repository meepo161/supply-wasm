(function () {
    'use strict';

    const apiRoot = 'http://localhost:8080';
    let pendingEventId = null;

    function validEventId(value) {
        const id = Number(value);
        return Number.isSafeInteger(id) && id > 0 ? id : null;
    }

    function readHash() {
        const match = window.location.hash.match(/^#(?:notification|eventId)=(\d+)$/);
        return match ? validEventId(match[1]) : null;
    }

    pendingEventId = readHash();

    if ('serviceWorker' in navigator) {
        navigator.serviceWorker.addEventListener('message', event => {
            if (event.data && event.data.type === 'OPEN_NOTIFICATION') {
                pendingEventId = validEventId(event.data.eventId);
                if (pendingEventId && window.erpPush.openListener) window.erpPush.openListener();
            }
        });
    }

    function applicationServerKey(value) {
        const padding = '='.repeat((4 - value.length % 4) % 4);
        const decoded = window.atob((value + padding).replace(/-/g, '+').replace(/_/g, '/'));
        return Uint8Array.from(decoded, character => character.charCodeAt(0));
    }

    async function enable() {
        if (!('serviceWorker' in navigator) || !('PushManager' in window) ||
            typeof Notification === 'undefined' || Notification.permission !== 'granted') {
            return false;
        }

        const registration = await navigator.serviceWorker.register('service-worker.js');
        await navigator.serviceWorker.ready;
        const keyResponse = await fetch(apiRoot + '/api/push/public-key', { credentials: 'include' });
        if (!keyResponse.ok) return false;
        const keyBody = await keyResponse.json();
        if (!keyBody.success || !keyBody.data.enabled || !keyBody.data.publicKey) return false;

        let subscription = await registration.pushManager.getSubscription();
        if (!subscription) {
            subscription = await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: applicationServerKey(keyBody.data.publicKey)
            });
        }
        const key = subscription.getKey('p256dh');
        const auth = subscription.getKey('auth');
        if (!key || !auth) return false;

        const response = await fetch(apiRoot + '/api/push/subscriptions', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                endpoint: subscription.endpoint,
                p256dh: btoa(String.fromCharCode.apply(null, new Uint8Array(key)))
                    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
                auth: btoa(String.fromCharCode.apply(null, new Uint8Array(auth)))
                    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
            })
        });
        return response.ok;
    }

    window.erpPush = {
        enable: enable,
        openListener: null,
        setOpenListener: function (listener) {
            this.openListener = listener;
            if (pendingEventId) listener();
        },
        consumePendingOpen: function () {
            const result = pendingEventId;
            pendingEventId = null;
            if (readHash()) {
                history.replaceState(null, '', window.location.pathname + window.location.search);
            }
            return result;
        }
    };
})();
