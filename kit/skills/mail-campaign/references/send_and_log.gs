/**
 * 팀 AI 키트 mail-campaign: 구글 시트 + Gmail 발송과 기록 (Google Apps Script)
 *
 * 준비 (한 번):
 *  1. 구글 시트를 만들고 시트 두 개를 둔다
 *     - "발송목록": build_sendlist.py 가 만든 for_sheet.csv 를 가져오기(파일 → 가져오기)
 *                  칸: 번호, 이메일, 회사명, 제목, 본문  (스크립트가 '발송상태', '보낸시각' 칸을 더한다)
 *     - "발송기록": 칸 campaign, email, company, sent_at  (data/sent_log.csv 와 같은 형식. 비어 있어도 됨)
 *  2. 확장 프로그램 → Apps Script 에 이 파일 내용을 붙여 넣고 CAMPAIGN 을 캠페인 이름으로 바꾼다
 *  3. 시트를 새로 고치면 "키트 발송" 메뉴가 생긴다. 처음 실행할 때 Gmail·시트 권한을 허용한다
 *
 * 안전장치: 보내기 전 확인 창, 한 번에 BATCH 명까지, 발송기록·발송상태로 중복 발송 차단, 하루 발송 한도 확인
 * 보낸 뒤: "발송기록" 시트를 CSV로 내려받아 data/sent_log.csv 로 저장하면 다음 build_sendlist.py 가 중복을 막는다
 */
const CAMPAIGN = '2026-12-ai-campus';   // ← 캠페인 이름
const BATCH = 50;                        // 한 번 실행에 보낼 최대 인원
const LIST_SHEET = '발송목록';
const LOG_SHEET = '발송기록';

function onOpen() {
  SpreadsheetApp.getUi().createMenu('키트 발송')
    .addItem('1) 나에게 미리보기 1통', 'previewToMe')
    .addItem('2) 발송 (확인 후)', 'sendBatch')
    .addToUi();
}

function readList_() {
  const sh = SpreadsheetApp.getActive().getSheetByName(LIST_SHEET);
  if (!sh) throw new Error('"' + LIST_SHEET + '" 시트가 없습니다');
  const values = sh.getDataRange().getValues();
  const head = values[0].map(String);
  ['이메일', '제목', '본문'].forEach(function (c) {
    if (head.indexOf(c) < 0) throw new Error('"' + LIST_SHEET + '" 시트에 "' + c + '" 칸이 없습니다');
  });
  ['발송상태', '보낸시각'].forEach(function (c) {
    if (head.indexOf(c) < 0) { sh.getRange(1, head.length + 1).setValue(c); head.push(c); }
  });
  return { sh: sh, head: head, rows: values.slice(1) };
}

function sentSet_() {
  const sh = SpreadsheetApp.getActive().getSheetByName(LOG_SHEET);
  const set = {};
  if (!sh) return set;
  const v = sh.getDataRange().getValues();
  for (var i = 1; i < v.length; i++) {
    if (String(v[i][0]).trim() === CAMPAIGN) set[String(v[i][1]).trim().toLowerCase()] = true;
  }
  return set;
}

function previewToMe() {
  const L = readList_();
  if (L.rows.length === 0) throw new Error('보낼 사람이 없습니다');
  const r = L.rows[0], me = Session.getActiveUser().getEmail();
  GmailApp.sendEmail(me, '[미리보기] ' + r[L.head.indexOf('제목')], String(r[L.head.indexOf('본문')]));
  SpreadsheetApp.getUi().alert('미리보기를 ' + me + ' 로 보냈습니다 (받는 사람: ' + r[L.head.indexOf('이메일')] + ')');
}

function sendBatch() {
  const ui = SpreadsheetApp.getUi();
  const L = readList_(), sent = sentSet_();
  const iE = L.head.indexOf('이메일'), iS = L.head.indexOf('제목'), iB = L.head.indexOf('본문');
  const iC = L.head.indexOf('회사명'), iSt = L.head.indexOf('발송상태'), iT = L.head.indexOf('보낸시각');
  const todo = [];
  L.rows.forEach(function (r, i) {
    const email = String(r[iE]).trim().toLowerCase();
    if (!email || String(r[iSt] || '') === '보냄' || sent[email]) return;
    todo.push(i);
  });
  const quota = MailApp.getRemainingDailyQuota();
  const n = Math.min(todo.length, BATCH, quota);
  if (n === 0) { ui.alert(todo.length === 0 ? '보낼 사람이 없습니다 (모두 보냄 또는 발송기록에 있음)' : '오늘 발송 한도가 남지 않았습니다'); return; }
  const ok = ui.alert('발송 확인', '캠페인 ' + CAMPAIGN + ': ' + n + '명에게 보냅니다 (남은 대상 ' + todo.length + '명, 오늘 남은 한도 ' + quota + '). 계속할까요?', ui.ButtonSet.YES_NO);
  if (ok !== ui.Button.YES) return;
  var log = SpreadsheetApp.getActive().getSheetByName(LOG_SHEET);
  if (!log) { log = SpreadsheetApp.getActive().insertSheet(LOG_SHEET); log.appendRow(['campaign', 'email', 'company', 'sent_at']); }
  var done = 0, failed = [];
  for (var k = 0; k < n; k++) {
    const i = todo[k], r = L.rows[i], row = i + 2;
    try {
      GmailApp.sendEmail(String(r[iE]).trim(), String(r[iS]), String(r[iB]));
      const now = new Date().toISOString();
      L.sh.getRange(row, iSt + 1).setValue('보냄');
      L.sh.getRange(row, iT + 1).setValue(now);
      log.appendRow([CAMPAIGN, String(r[iE]).trim(), iC >= 0 ? r[iC] : '', now]);
      done++;
    } catch (e) {
      L.sh.getRange(row, iSt + 1).setValue('실패: ' + e.message);
      failed.push(r[iE]);
    }
  }
  ui.alert('보냄 ' + done + '건' + (failed.length ? ', 실패 ' + failed.length + '건: ' + failed.join(', ') : '') +
           (todo.length > n ? '\n남은 대상 ' + (todo.length - n) + '명은 다시 실행하면 이어서 보냅니다' : ''));
}
