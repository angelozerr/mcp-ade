/**
 * Reusable chip-based language filter for server lists (LSP / DAP).
 *
 * Usage:
 *   const filter = new LanguageFilter(container, () => window.lspConfigs, () => reRenderList());
 *   const filtered = filter.filterServers(allServers);
 *   filter.getItemsContainer().innerHTML = filtered.map(...).join('');
 */
class LanguageFilter {

    constructor(parentContainer, getConfigs, onFilterChange) {
        this._parent = parentContainer;
        this._getConfigs = getConfigs;
        this._onFilterChange = onFilterChange;
        this._selectedLanguages = [];
        this._highlightedIndex = -1;
        this._createDOM();
    }

    getItemsContainer() {
        return this._itemsContainer;
    }

    filterServers(servers) {
        if (this._selectedLanguages.length === 0) return servers;
        return servers.filter(server => {
            if (!server.documentSelector || server.documentSelector.length === 0) {
                return false;
            }
            const langs = new Set();
            for (const sel of server.documentSelector) {
                if (sel.language) langs.add(sel.language);
            }
            return this._selectedLanguages.some(l => langs.has(l));
        });
    }

    // ── private ──────────────────────────────────────────

    _createDOM() {
        this._parent.classList.add('language-filter-active');

        // Filter bar
        this._bar = document.createElement('div');
        this._bar.className = 'language-filter-bar';

        this._inputContainer = document.createElement('div');
        this._inputContainer.className = 'language-filter-input-container';
        this._inputContainer.addEventListener('click', () => this._input.focus());

        this._input = document.createElement('input');
        this._input.type = 'text';
        this._input.className = 'language-filter-input';
        this._input.placeholder = 'Filter by language…';
        this._inputContainer.appendChild(this._input);

        this._dropdown = document.createElement('div');
        this._dropdown.className = 'language-filter-dropdown';

        this._bar.appendChild(this._inputContainer);
        this._bar.appendChild(this._dropdown);

        // Items container
        this._itemsContainer = document.createElement('div');
        this._itemsContainer.className = 'language-filter-items';

        // Move existing children into items container
        while (this._parent.firstChild) {
            this._itemsContainer.appendChild(this._parent.firstChild);
        }

        this._parent.appendChild(this._bar);
        this._parent.appendChild(this._itemsContainer);

        this._bindEvents();

        this._input.focus();
    }

    _bindEvents() {
        this._input.addEventListener('input', () => {
            this._showDropdown(this._input.value);
        });

        // Defer focus listener so the initial focus() doesn't open the dropdown
        requestAnimationFrame(() => {
            this._input.addEventListener('focus', () => {
                if (this._suppressFocusDropdown) {
                    this._suppressFocusDropdown = false;
                    return;
                }
                this._showDropdown(this._input.value);
            });
        });

        this._input.addEventListener('keydown', (e) => {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                this._moveHighlight(1);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                this._moveHighlight(-1);
            } else if (e.key === 'Enter') {
                e.preventDefault();
                this._selectHighlighted();
            } else if (e.key === 'Backspace' && this._input.value === '') {
                if (this._selectedLanguages.length > 0) {
                    this._removeLanguage(this._selectedLanguages[this._selectedLanguages.length - 1]);
                }
            } else if (e.key === 'Escape') {
                this._hideDropdown();
                this._input.value = '';
            } else if (e.key === ' ' && e.ctrlKey) {
                e.preventDefault();
                this._showDropdown(this._input.value);
            }
        });

        // Close dropdown on outside click
        document.addEventListener('mousedown', (e) => {
            if (!this._bar.contains(e.target)) {
                this._hideDropdown();
            }
        });
    }

    _extractAllLanguages() {
        const configs = this._getConfigs();
        const langs = new Set();
        for (const cfg of Object.values(configs || {})) {
            if (cfg.documentSelector) {
                for (const sel of cfg.documentSelector) {
                    if (sel.language) langs.add(sel.language);
                }
            }
        }
        return Array.from(langs).sort();
    }

    _showDropdown(query) {
        const all = this._extractAllLanguages();
        const q = (query || '').toLowerCase();
        const options = all
            .filter(l => !this._selectedLanguages.includes(l))
            .filter(l => !q || l.toLowerCase().includes(q));

        if (options.length === 0) {
            this._hideDropdown();
            return;
        }

        this._highlightedIndex = 0;
        this._dropdown.innerHTML = options.map((lang, i) =>
            `<div class="language-filter-option${i === 0 ? ' highlighted' : ''}">${lang}</div>`
        ).join('');
        this._dropdown.classList.add('visible');

        // Bind click on each option
        this._dropdown.querySelectorAll('.language-filter-option').forEach(el => {
            el.addEventListener('mousedown', (e) => {
                e.preventDefault();
                this._addLanguage(el.textContent);
            });
        });
    }

    _hideDropdown() {
        this._dropdown.classList.remove('visible');
        this._highlightedIndex = -1;
    }

    _moveHighlight(direction) {
        const options = this._dropdown.querySelectorAll('.language-filter-option');
        if (options.length === 0) return;

        if (this._highlightedIndex >= 0 && this._highlightedIndex < options.length) {
            options[this._highlightedIndex].classList.remove('highlighted');
        }

        this._highlightedIndex += direction;
        if (this._highlightedIndex < 0) this._highlightedIndex = options.length - 1;
        if (this._highlightedIndex >= options.length) this._highlightedIndex = 0;

        options[this._highlightedIndex].classList.add('highlighted');
        options[this._highlightedIndex].scrollIntoView({ block: 'nearest' });
    }

    _selectHighlighted() {
        const options = this._dropdown.querySelectorAll('.language-filter-option');
        if (this._highlightedIndex >= 0 && this._highlightedIndex < options.length) {
            this._addLanguage(options[this._highlightedIndex].textContent);
        } else if (options.length === 1) {
            this._addLanguage(options[0].textContent);
        }
    }

    _addLanguage(lang) {
        if (this._selectedLanguages.includes(lang)) return;
        this._selectedLanguages.push(lang);
        this._renderChip(lang);
        this._input.value = '';
        this._hideDropdown();
        this._onFilterChange();
    }

    _removeLanguage(lang) {
        this._selectedLanguages = this._selectedLanguages.filter(l => l !== lang);
        const chip = this._inputContainer.querySelector(`[data-lang="${lang}"]`);
        if (chip) chip.remove();
        this._onFilterChange();
        this._suppressFocusDropdown = true;
        this._input.focus();
    }

    _renderChip(lang) {
        const chip = document.createElement('span');
        chip.className = 'language-filter-chip';
        chip.dataset.lang = lang;
        chip.textContent = lang;

        const remove = document.createElement('span');
        remove.className = 'language-filter-chip-remove';
        remove.textContent = '×';
        remove.addEventListener('click', (e) => {
            e.stopPropagation();
            this._removeLanguage(lang);
        });

        chip.appendChild(remove);
        this._inputContainer.insertBefore(chip, this._input);
    }
}
