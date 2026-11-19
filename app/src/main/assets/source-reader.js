(() => {
    'use strict';
    if (window.top !== window || !/^https?:$/.test(location.protocol)) return;
    const id = 'sipan-source-reader-style';
    const applyStyle = () => {
        if (!document.documentElement || document.getElementById(id)) return;
        const style = document.createElement('style');
        style.id = id;
        // Leave content images, chapter navigation, and ordinary embeds alone.
        style.textContent = `
            .adsbygoogle, .google-auto-placed, .ad-banner, .ad-container, .ad-overlay,
            [data-ad-slot], [data-ad-unit], [id^="div-gpt-ad-"], iframe[id^="google_ads_iframe_"] {
                display: none !important;
            }`;
        (document.head || document.documentElement).appendChild(style);
    };
    if (document.documentElement) applyStyle();
    else document.addEventListener('DOMContentLoaded', applyStyle, { once: true });
})();
