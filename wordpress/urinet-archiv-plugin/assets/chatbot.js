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
				var text = stream.split('\n').filter(function (line) {
					return line.indexOf('data:') === 0;
				}).map(function (line) {
					try {
						var data = JSON.parse(line.slice(5).trim());
						return data.delta || data.hinweis || '';
					} catch (error) {
						return '';
					}
				}).join('');
				ausgabe.textContent = text || 'Es wurde keine Antwort gefunden.';
			}).catch(function () {
				ausgabe.textContent = 'Der Chatbot ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.';
			});
		});
	});
}());
