function openReclip() {
  chrome.runtime.sendNativeMessage("com.kerim.reclip", { action: "show" }, () => {
    if (chrome.runtime.lastError) {
      console.warn("Reclip native host error:", chrome.runtime.lastError.message);
    }
  });
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "reclip-open",
    title: "Open Reclip Clipboard",
    contexts: ["all"]
  });
});

chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === "reclip-open") {
    openReclip();
  }
});

chrome.action.onClicked.addListener(() => {
  openReclip();
});
