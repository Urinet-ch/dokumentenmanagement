(function () {
	'use strict';
	document.querySelectorAll('[data-urinet-chatbot]').forEach(function (box) {
		var frage = box.querySelector('[data-urinet-chat-frage]');
		var ausgabe = box.querySelector('[data-urinet-chat-ausgabe]');
		box.querySelector('[data-urinet-chat-senden]').addEventListener('click', function () {
			var text = frage.value.trim();
			if (!text || text.length > 500) {
				ausgabe.textContent = 'Bitte geben Sie eine Frage mit höchstens 500 Zeichen ein.';
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
				var text = '';
				var ereignis = '';
				var daten = [];
				function verarbeiten() {
					if (!daten.length) return;
					var data = JSON.parse(daten.join('\n'));
					if ('text' === ereignis) text += data.delta || '';
					if ('quellen' === ereignis) quellen = data;
					if ('fehler' === ereignis) throw new Error(data.meldung || 'chat');
					if ('fertig' === ereignis && data.hinweis) text += '\n' + data.hinweis;
					daten = [];
				}
				stream.split('\n').forEach(function (line) {
					if (line.indexOf('event:') === 0) {
						ereignis = line.slice(6).trim();
					} else if (line.indexOf('data:') === 0) {
						daten.push(line.slice(5).trim());
					} else if (!line.trim()) {
						try { verarbeiten(); } catch (error) { throw error; }
					}
				});
				verarbeiten();
				ausgabe.textContent = text || 'Es wurde keine Antwort gefunden.';
				quellen.forEach(function (quelle) {
					if (quelle.dokumentId) {
						var link = document.createElement('a');
						link.href = window.urinetArchivChatbot.dokumentUrl + encodeURIComponent(quelle.dokumentId);
						var reihe = quelle.reihe && (quelle.reihe.name || quelle.reihe.code);
						var details = [];
						if (reihe) details.push(reihe);
						if (quelle.ausgabe) details.push(quelle.ausgabe);
						link.textContent = 'Quelle: ' + (quelle.titel || quelle.dokumentId)
							+ (details.length ? ' (' + details.join(', ') + ')' : '');
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
