export function getMappedStatus(sonarStatus) {
    if (!sonarStatus) return 'accept';
    const s = String(sonarStatus).toUpperCase();
    if (s === 'ACCEPTED') return 'accept';
    if (s === 'CONFIRMED') return 'confirm';
    if (s === 'FALSE_POSITIVE') return 'falsepositive';
    if (s === 'FIXED' || s === 'RESOLVED') return 'resolve';
    if (s === 'WONTFIX') return 'wontfix';
    return 'accept';
}

export function getReopenTransition(status) {
    if (status === 'CONFIRMED') return 'unconfirm';
    return 'reopen';
}