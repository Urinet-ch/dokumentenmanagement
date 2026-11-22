(function () {
	'use strict';
	document.querySelectorAll('[data-urinet-chatbot]').forEach(function (box) {
		var frage = box.querySelector('[data-urinet-chat-frage]');
		var ausgabe = box.querySelector('[data-urinet-chat-ausgabe]');
		box.querySelector('[data-urinet-chat-senden]').addEventListener('click', function () {
			var text = frage.value.trim();
			if (!text || text.length > 4000) {
				ausgabe.textContent = 'Bitte geben Sie eine Frage mit höchstens 4’000 Zeichen ein.';
				return;
			}
			ausgabe.textContent = 'Die Antwort wird gesucht …';
			fetch(window.urinetArchivChatbot.url, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ frage: text })
			}).then(function (response) {
				if (!response.ok) throw new Error('chat');
				return response.text();
			}).then(function (stream) {
				var quellen = [];
				var text = stream.split('\n').filter(function (line) {
					return line.indexOf('data:') === 0;
				}).map(function (line) {
					try {
						var data = JSON.parse(line.slice(5).trim());
						if (data.typ === 'quellen' || data.quellen) {
							quellen = quellen.concat(data.quellen || []);
							return '';
						}
						if (data.typ === 'fehler' || data.fehler) {
							throw new Error(data.meldung || (data.fehler && data.fehler.meldung) || 'chat');
						}
						return data.delta || data.text || data.hinweis || '';
					} catch (error) {
						return '';
					}
				}).join('');
				ausgabe.textContent = text || 'Es wurde keine Antwort gefunden.';
				quellen.forEach(function (quelle) {
					if (quelle.url) {
						var link = document.createElement('a');
						link.href = quelle.url;
						link.textContent = 'Quelle: ' + (quelle.titel || quelle.id || 'Dokument');
						ausgabe.appendChild(document.createElement('br'));
						ausgabe.appendChild(link);
					}
				});
			}).catch(function (error) {
				ausgabe.textContent = error.message && 'chat' !== error.message
					? error.message
					: 'Der Chatbot ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.';
			});
		});
	});
}());
