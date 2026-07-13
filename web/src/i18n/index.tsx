import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

// Lightweight in-house i18n (EN/VI) — no external dependency, in keeping with the rest of
// the app. Strings live in the dictionary below; components read them via useI18n().t('key').
// `t` supports {var} interpolation, e.g. t('analytics.dayOf', { d: 3, n: 31 }).

export type Lang = 'en' | 'vi'
const STORAGE_KEY = 'vernfy_lang'

type Dict = Record<string, string>

const en: Dict = {
  'common.loading': 'Loading…',
  'common.optional': 'Optional',
  'common.select': 'Select…',
  'common.none': 'None',

  'lang.label': 'Language',
  'lang.en': 'EN',
  'lang.vi': 'VI',

  'nav.dashboard': 'Dashboard',
  'nav.transactions': 'Transactions',
  'nav.budgets': 'Budgets',
  'nav.wallets': 'Wallets',
  'nav.analytics': 'Analytics',
  'nav.admin': 'Admin',
  'nav.signOut': 'Sign out',
  'nav.tour': 'Guided tour',
  'nav.menu': 'Menu',
  'nav.luckyme': 'LuckyMe',
  'footer.tagline': 'event-driven finance platform',

  'luckyme.title': 'LuckyMe',
  'luckyme.subtitle': 'Flip the coin and try your luck.',
  'luckyme.hint': 'Red = you get logged out. Green = mini games.',
  'luckyme.flip': 'Flip the coin',
  'luckyme.flipping': 'Flipping…',
  'luckyme.games.title': 'Mini games',
  'luckyme.games.subtitle': 'Pick your game — all four are on the way.',
  'luckyme.games.soon': 'Coming soon',
  'luckyme.game.roulette.name': 'Roulette',
  'luckyme.game.roulette.desc': 'Spin the wheel and land on red, black or green.',
  'luckyme.game.blackjack.name': 'Blackjack 21',
  'luckyme.game.blackjack.desc': 'Hit, stand or double — get as close to 21 as you dare.',
  'luckyme.game.duckrace.name': 'Duck race',
  'luckyme.game.duckrace.desc': 'Back a duck and bet on it, horse-race style.',
  'luckyme.game.dice.name': 'Two dice',
  'luckyme.game.dice.desc': 'Roll two dice — a red total loses, a green total wins.',

  'login.title': 'Sign in to Vernfy',
  'login.email': 'Email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.submitting': 'Signing in…',
  'login.noAccount': 'No account?',
  'login.createOne': 'Create one',

  'register.title': 'Create your Vernfy account',
  'register.username': 'Username',
  'register.email': 'Email',
  'register.password': 'Password (min 8 chars)',
  'register.submit': 'Create account',
  'register.submitting': 'Creating…',
  'register.haveAccount': 'Already have an account?',
  'register.signIn': 'Sign in',

  'dashboard.netBalance': 'Net balance',
  'dashboard.income': 'Income',
  'dashboard.expense': 'Expense',
  'dashboard.savingsRateNone': 'Savings rate —',
  'dashboard.savedPct': '{pct}% saved',
  'dashboard.balanceTrend': 'Balance trend',
  'dashboard.trendEmpty': 'Add transactions to see your balance trend.',
  'dashboard.spendingByCategory': 'Spending by category',
  'dashboard.categoryEmpty': 'Add an expense to see the breakdown.',
  'dashboard.recent': 'Recent transactions',
  'dashboard.noTx': 'No transactions yet.',
  'dashboard.budgets': 'Budgets',
  'dashboard.noBudgets': 'No budgets yet.',

  'tx.new': 'New transaction',
  'tx.type': 'Type',
  'tx.amount': 'Amount',
  'tx.category': 'Category',
  'tx.wallet': 'Wallet',
  'tx.fromWallet': 'From wallet',
  'tx.toWallet': 'To wallet',
  'tx.description': 'Description',
  'tx.date': 'Date',
  'tx.add': 'Add transaction',
  'tx.saving': 'Saving…',
  'tx.title': 'Transactions',
  'tx.expense': 'Expense',
  'tx.income': 'Income',
  'tx.transfer': 'Transfer',
  'tx.colDate': 'Date',
  'tx.colCategory': 'Category',
  'tx.colDescription': 'Description',
  'tx.colAmount': 'Amount',
  'tx.currencyLocked': 'Currency is set by the selected wallet',
  'tx.errAmount': 'Enter an amount greater than 0.',
  'tx.errWallets': 'Choose both a source and a destination wallet.',
  'tx.errSameWallet': 'Source and destination wallets must be different.',
  'tx.errTransferCat': 'Transfer category is unavailable.',

  'budget.new': 'New budget',
  'budget.name': 'Name',
  'budget.category': 'Category',
  'budget.period': 'Period',
  'budget.limit': 'Limit amount',
  'budget.start': 'Start',
  'budget.end': 'End',
  'budget.dateHint': 'Auto-filled from the period — edit if you need a different range.',
  'budget.needWallet': 'Create a wallet first',
  'budget.needWalletHint': 'A budget caps your spending, so you need at least one wallet before setting one.',
  'budget.createWallet': 'Create a wallet',
  'budget.overBalance': 'Limit is higher than your available balance ({balance}). You can still save.',
  'budget.add': 'Add budget',
  'budget.saving': 'Saving…',
  'budget.title': 'Budgets',
  'budget.over': 'Over budget',
  'budget.errLimit': 'Enter a limit greater than 0.',
  'period.MONTHLY': 'Monthly',
  'period.WEEKLY': 'Weekly',
  'period.YEARLY': 'Yearly',
  'period.CUSTOM': 'Custom',

  'wallet.new': 'New wallet',
  'wallet.name': 'Name',
  'wallet.namePlaceholder': 'e.g. Checking',
  'wallet.type': 'Type',
  'wallet.opening': 'Opening balance',
  'wallet.add': 'Add wallet',
  'wallet.saving': 'Saving…',
  'wallet.title': 'Wallets',
  'wallet.total': 'Total',
  'wallet.delete': 'Delete',
  'wallet.deleteTitle': 'Delete wallet',
  'wallet.empty': 'No wallets yet. Create one to track balances and transfers.',
  'wallet.errName': 'Enter a wallet name.',
  'kind.CASH': 'Cash',
  'kind.BANK': 'Bank',
  'kind.CARD': 'Card',
  'kind.SAVINGS': 'Savings',
  'kind.OTHER': 'Other',

  'an.title': 'Analytics',
  'an.subtitle': 'built from your transaction history',
  'an.thisMonth': 'This month',
  'an.monthlySummary': 'Monthly summary',
  'an.ai': 'AI',
  'an.ruleBased': 'Rule-based',
  'an.savingsRate': 'Savings rate',
  'an.income': 'Income',
  'an.expense': 'Expense',
  'an.net': 'Net',
  'an.netHint': 'income − expense',
  'an.vsLastMonth': 'vs last month',
  'an.forecast': 'Spend forecast',
  'an.forecastHint': 'projected by month-end at the current pace',
  'an.dayOf': 'Day {d} of {n}',
  'an.soFar': '{amount} so far',
  'an.averagingPre': 'Averaging',
  'an.averagingPost': '/ day',
  'an.topMovers': 'Top movers vs last month',
  'an.noCategory': 'No category activity yet.',
  'an.spendingByCategory': 'Spending by category',
  'an.noSpending': 'No spending recorded for this period.',
  'an.new': 'new',

  'notif.title': 'Notifications',
  'notif.markAll': 'Mark all read',
  'notif.empty': 'No notifications',
  'notif.loading': 'Loading…',
  'time.justNow': 'just now',
  'time.mAgo': '{m}m ago',
  'time.hAgo': '{h}h ago',
  'time.dAgo': '{d}d ago',

  'tour.guide': 'Vera · your guide',
  'tour.skip': 'Skip',
  'tour.back': 'Back',
  'tour.next': 'Next',
  'tour.getStarted': 'Get started',
  'tour.welcome.title': "Hi, I'm Vera!",
  'tour.welcome.body':
    'Welcome to Vernfy. Let me point out each part of the app so you know exactly what it does. It only takes a minute — tap Next.',
  'tour.hero.title': 'Your net balance',
  'tour.hero.body':
    'This big number is income minus expenses. The pill on the right is your savings rate — the share of income you kept this month.',
  'tour.trend.title': 'Balance trend',
  'tour.trend.body':
    'Your running balance over time. A rising line means you are saving; a falling line means spending outpaced income.',
  'tour.categories.title': 'Spending by category',
  'tour.categories.body':
    'Where your money actually goes, tallest bar first. A quick way to spot the category eating your budget.',
  'tour.recent.title': 'Recent transactions',
  'tour.recent.body': 'Your latest activity at a glance. Green is income, red is an expense.',
  'tour.budgets.title': 'Budget snapshot',
  'tour.budgets.body':
    'How close each budget is to its limit. The bar turns red the moment you go over. Now let me show you where you create things →',
  'tour.txForm.title': 'Add a transaction',
  'tour.txForm.body':
    'Record income, an expense, or a transfer between wallets. Pick a type, amount, category and date, then Add — that is the core action of the app.',
  'tour.txList.title': 'Your transaction history',
  'tour.txList.body':
    'Every transaction you add shows up here. This is the raw data everything else — dashboard, budgets, analytics — is built from.',
  'tour.budgetForm.title': 'Create a budget',
  'tour.budgetForm.body':
    'Set a spending limit for a category over a period (e.g. Food, Monthly, 600). You only set the limit — Vernfy fills in how much you have spent.',
  'tour.budgetList.title': 'Budgets track themselves',
  'tour.budgetList.body':
    'The “spent” amount updates automatically as matching transactions arrive — no manual tallying. The bar goes red when you overspend.',
  'tour.walletForm.title': 'Add a wallet',
  'tour.walletForm.body':
    'A wallet is a real account: cash, bank, card or savings. Give it a name, type and opening balance.',
  'tour.walletList.title': 'Balances update on their own',
  'tour.walletList.body':
    'Each wallet balance rises and falls automatically with every transaction and transfer you record. No need to edit balances by hand.',
  'tour.anSummary.title': 'Your month, in plain English',
  'tour.anSummary.body':
    'Vernfy automatically writes a summary of your finances — savings rate, biggest category, income vs expense — so you do not have to read charts.',
  'tour.anForecast.title': 'Spend forecast',
  'tour.anForecast.body':
    'Based on your pace so far this month, this projects where your spending will land by month-end — an early warning before you overspend.',
  'tour.bell.title': 'Smart alerts',
  'tour.bell.body':
    'Vernfy watches your activity in the background. Unusual spending? This bell lights up with an instant alert — no need to go looking.',
  'tour.help.title': 'Replay anytime',
  'tour.help.body': 'Want this tour again later? Just tap this “?” button. You can never get lost.',
  'tour.finish.title': "You're all set!",
  'tour.finish.body':
    'That is the whole app. The best first move: add a transaction and watch your dashboard, budgets and analytics come alive. Enjoy Vernfy!',
}

const vi: Dict = {
  'common.loading': 'Đang tải…',
  'common.optional': 'Không bắt buộc',
  'common.select': 'Chọn…',
  'common.none': 'Không',

  'lang.label': 'Ngôn ngữ',
  'lang.en': 'EN',
  'lang.vi': 'VI',

  'nav.dashboard': 'Tổng quan',
  'nav.transactions': 'Giao dịch',
  'nav.budgets': 'Ngân sách',
  'nav.wallets': 'Ví',
  'nav.analytics': 'Phân tích',
  'nav.admin': 'Quản trị',
  'nav.signOut': 'Đăng xuất',
  'nav.tour': 'Hướng dẫn',
  'nav.menu': 'Menu',
  'nav.luckyme': 'LuckyMe',
  'footer.tagline': 'nền tảng tài chính hướng sự kiện',

  'luckyme.title': 'LuckyMe',
  'luckyme.subtitle': 'Lật đồng xu và thử vận may của bạn.',
  'luckyme.hint': 'Mặt đỏ = bị đăng xuất. Mặt xanh = mini game.',
  'luckyme.flip': 'Lật đồng xu',
  'luckyme.flipping': 'Đang lật…',
  'luckyme.games.title': 'Trò chơi mini',
  'luckyme.games.subtitle': 'Chọn trò chơi — cả bốn trò đều sắp ra mắt.',
  'luckyme.games.soon': 'Sắp ra mắt',
  'luckyme.game.roulette.name': 'Roulette',
  'luckyme.game.roulette.desc': 'Quay vòng quay và dừng ở khe đỏ, đen hoặc xanh lá.',
  'luckyme.game.blackjack.name': 'Blackjack 21 nút',
  'luckyme.game.blackjack.desc': 'Hit, stand hay double — về càng sát 21 nút càng tốt.',
  'luckyme.game.duckrace.name': 'Đua vịt',
  'luckyme.game.duckrace.desc': 'Chọn một chú vịt và đặt cược, y như đua ngựa.',
  'luckyme.game.dice.name': 'Tung hai xúc xắc',
  'luckyme.game.dice.desc': 'Tung hai xúc xắc — tổng đỏ là thua, tổng xanh lá là thắng.',

  'login.title': 'Đăng nhập Vernfy',
  'login.email': 'Email',
  'login.password': 'Mật khẩu',
  'login.submit': 'Đăng nhập',
  'login.submitting': 'Đang đăng nhập…',
  'login.noAccount': 'Chưa có tài khoản?',
  'login.createOne': 'Tạo ngay',

  'register.title': 'Tạo tài khoản Vernfy',
  'register.username': 'Tên đăng nhập',
  'register.email': 'Email',
  'register.password': 'Mật khẩu (tối thiểu 8 ký tự)',
  'register.submit': 'Tạo tài khoản',
  'register.submitting': 'Đang tạo…',
  'register.haveAccount': 'Đã có tài khoản?',
  'register.signIn': 'Đăng nhập',

  'dashboard.netBalance': 'Số dư ròng',
  'dashboard.income': 'Thu nhập',
  'dashboard.expense': 'Chi tiêu',
  'dashboard.savingsRateNone': 'Tỷ lệ tiết kiệm —',
  'dashboard.savedPct': 'Tiết kiệm {pct}%',
  'dashboard.balanceTrend': 'Diễn biến số dư',
  'dashboard.trendEmpty': 'Thêm giao dịch để xem diễn biến số dư.',
  'dashboard.spendingByCategory': 'Chi tiêu theo danh mục',
  'dashboard.categoryEmpty': 'Thêm một khoản chi để xem phân tích.',
  'dashboard.recent': 'Giao dịch gần đây',
  'dashboard.noTx': 'Chưa có giao dịch nào.',
  'dashboard.budgets': 'Ngân sách',
  'dashboard.noBudgets': 'Chưa có ngân sách nào.',

  'tx.new': 'Giao dịch mới',
  'tx.type': 'Loại',
  'tx.amount': 'Số tiền',
  'tx.category': 'Danh mục',
  'tx.wallet': 'Ví',
  'tx.fromWallet': 'Từ ví',
  'tx.toWallet': 'Đến ví',
  'tx.description': 'Mô tả',
  'tx.date': 'Ngày',
  'tx.add': 'Thêm giao dịch',
  'tx.saving': 'Đang lưu…',
  'tx.title': 'Giao dịch',
  'tx.expense': 'Chi',
  'tx.income': 'Thu',
  'tx.transfer': 'Chuyển khoản',
  'tx.colDate': 'Ngày',
  'tx.colCategory': 'Danh mục',
  'tx.colDescription': 'Mô tả',
  'tx.colAmount': 'Số tiền',
  'tx.currencyLocked': 'Tiền tệ được đặt theo ví đã chọn',
  'tx.errAmount': 'Nhập số tiền lớn hơn 0.',
  'tx.errWallets': 'Chọn cả ví nguồn và ví đích.',
  'tx.errSameWallet': 'Ví nguồn và ví đích phải khác nhau.',
  'tx.errTransferCat': 'Không có danh mục chuyển khoản.',

  'budget.new': 'Ngân sách mới',
  'budget.name': 'Tên',
  'budget.category': 'Danh mục',
  'budget.period': 'Kỳ hạn',
  'budget.limit': 'Hạn mức',
  'budget.start': 'Bắt đầu',
  'budget.end': 'Kết thúc',
  'budget.dateHint': 'Tự điền theo kỳ hạn — sửa lại nếu bạn muốn khoảng thời gian khác.',
  'budget.needWallet': 'Hãy tạo ví trước',
  'budget.needWalletHint': 'Ngân sách để giới hạn chi tiêu, nên bạn cần có ít nhất một ví trước khi lập.',
  'budget.createWallet': 'Tạo ví',
  'budget.overBalance': 'Hạn mức lớn hơn số dư hiện có ({balance}). Bạn vẫn có thể lưu.',
  'budget.add': 'Thêm ngân sách',
  'budget.saving': 'Đang lưu…',
  'budget.title': 'Ngân sách',
  'budget.over': 'Vượt hạn mức',
  'budget.errLimit': 'Nhập hạn mức lớn hơn 0.',
  'period.MONTHLY': 'Hàng tháng',
  'period.WEEKLY': 'Hàng tuần',
  'period.YEARLY': 'Hàng năm',
  'period.CUSTOM': 'Tuỳ chỉnh',

  'wallet.new': 'Ví mới',
  'wallet.name': 'Tên',
  'wallet.namePlaceholder': 'VD: Tài khoản chính',
  'wallet.type': 'Loại',
  'wallet.opening': 'Số dư ban đầu',
  'wallet.add': 'Thêm ví',
  'wallet.saving': 'Đang lưu…',
  'wallet.title': 'Ví',
  'wallet.total': 'Tổng',
  'wallet.delete': 'Xoá',
  'wallet.deleteTitle': 'Xoá ví',
  'wallet.empty': 'Chưa có ví nào. Tạo một ví để theo dõi số dư và chuyển khoản.',
  'wallet.errName': 'Nhập tên ví.',
  'kind.CASH': 'Tiền mặt',
  'kind.BANK': 'Ngân hàng',
  'kind.CARD': 'Thẻ',
  'kind.SAVINGS': 'Tiết kiệm',
  'kind.OTHER': 'Khác',

  'an.title': 'Phân tích',
  'an.subtitle': 'dựng từ lịch sử giao dịch của bạn',
  'an.thisMonth': 'Tháng này',
  'an.monthlySummary': 'Tóm tắt tháng',
  'an.ai': 'AI',
  'an.ruleBased': 'Theo quy tắc',
  'an.savingsRate': 'Tỷ lệ tiết kiệm',
  'an.income': 'Thu nhập',
  'an.expense': 'Chi tiêu',
  'an.net': 'Ròng',
  'an.netHint': 'thu − chi',
  'an.vsLastMonth': 'so với tháng trước',
  'an.forecast': 'Dự báo chi tiêu',
  'an.forecastHint': 'dự kiến đến cuối tháng theo tốc độ hiện tại',
  'an.dayOf': 'Ngày {d}/{n}',
  'an.soFar': 'đã chi {amount}',
  'an.averagingPre': 'Trung bình',
  'an.averagingPost': '/ ngày',
  'an.topMovers': 'Biến động lớn so với tháng trước',
  'an.noCategory': 'Chưa có hoạt động danh mục.',
  'an.spendingByCategory': 'Chi tiêu theo danh mục',
  'an.noSpending': 'Chưa ghi nhận chi tiêu trong kỳ này.',
  'an.new': 'mới',

  'notif.title': 'Thông báo',
  'notif.markAll': 'Đánh dấu đã đọc',
  'notif.empty': 'Không có thông báo',
  'notif.loading': 'Đang tải…',
  'time.justNow': 'vừa xong',
  'time.mAgo': '{m} phút trước',
  'time.hAgo': '{h} giờ trước',
  'time.dAgo': '{d} ngày trước',

  'tour.guide': 'Vera · người hướng dẫn',
  'tour.skip': 'Bỏ qua',
  'tour.back': 'Quay lại',
  'tour.next': 'Tiếp',
  'tour.getStarted': 'Bắt đầu',
  'tour.welcome.title': 'Chào bạn, mình là Vera!',
  'tour.welcome.body':
    'Chào mừng đến Vernfy. Để mình chỉ cho bạn từng phần của ứng dụng để bạn biết rõ mỗi chỗ làm gì. Chỉ mất một phút thôi — bấm Tiếp nhé.',
  'tour.hero.title': 'Số dư ròng của bạn',
  'tour.hero.body':
    'Con số lớn này là thu nhập trừ chi tiêu. Huy hiệu bên phải là tỷ lệ tiết kiệm — phần thu nhập bạn giữ lại trong tháng.',
  'tour.trend.title': 'Diễn biến số dư',
  'tour.trend.body':
    'Số dư của bạn theo thời gian. Đường đi lên nghĩa là bạn đang tiết kiệm; đi xuống nghĩa là chi nhiều hơn thu.',
  'tour.categories.title': 'Chi tiêu theo danh mục',
  'tour.categories.body':
    'Tiền của bạn thực sự đi đâu, cột cao nhất trước. Cách nhanh để phát hiện danh mục đang ngốn ngân sách.',
  'tour.recent.title': 'Giao dịch gần đây',
  'tour.recent.body': 'Hoạt động mới nhất của bạn trong nháy mắt. Xanh là thu, đỏ là chi.',
  'tour.budgets.title': 'Ảnh chụp ngân sách',
  'tour.budgets.body':
    'Mỗi ngân sách đã gần chạm hạn mức thế nào. Thanh chuyển đỏ ngay khi bạn vượt. Giờ để mình chỉ nơi bạn tạo dữ liệu →',
  'tour.txForm.title': 'Thêm một giao dịch',
  'tour.txForm.body':
    'Ghi khoản thu, khoản chi, hoặc chuyển tiền giữa các ví. Chọn loại, số tiền, danh mục và ngày rồi bấm Thêm — đây là thao tác cốt lõi.',
  'tour.txList.title': 'Lịch sử giao dịch',
  'tour.txList.body':
    'Mọi giao dịch bạn thêm sẽ hiện ở đây. Đây là dữ liệu gốc mà mọi thứ khác — tổng quan, ngân sách, phân tích — đều dựa vào.',
  'tour.budgetForm.title': 'Tạo ngân sách',
  'tour.budgetForm.body':
    'Đặt hạn mức chi cho một danh mục theo kỳ (VD: Ăn uống, Hàng tháng, 600). Bạn chỉ đặt hạn mức — Vernfy tự điền phần đã chi.',
  'tour.budgetList.title': 'Ngân sách tự theo dõi',
  'tour.budgetList.body':
    'Số “đã chi” tự cập nhật khi có giao dịch khớp — không cần cộng tay. Thanh chuyển đỏ khi bạn chi vượt.',
  'tour.walletForm.title': 'Thêm một ví',
  'tour.walletForm.body':
    'Ví là một tài khoản thật: tiền mặt, ngân hàng, thẻ hay tiết kiệm. Đặt tên, chọn loại và số dư ban đầu.',
  'tour.walletList.title': 'Số dư tự cập nhật',
  'tour.walletList.body':
    'Số dư mỗi ví tự tăng giảm theo từng giao dịch và lần chuyển khoản. Không cần sửa số dư bằng tay.',
  'tour.anSummary.title': 'Tháng của bạn, bằng lời',
  'tour.anSummary.body':
    'Vernfy tự viết một bản tóm tắt tài chính — tỷ lệ tiết kiệm, danh mục lớn nhất, thu so với chi — để bạn khỏi phải đọc biểu đồ.',
  'tour.anForecast.title': 'Dự báo chi tiêu',
  'tour.anForecast.body':
    'Dựa trên tốc độ chi từ đầu tháng, nó dự đoán chi tiêu của bạn sẽ tới đâu vào cuối tháng — cảnh báo sớm trước khi bạn tiêu lố.',
  'tour.bell.title': 'Cảnh báo thông minh',
  'tour.bell.body':
    'Vernfy âm thầm theo dõi hoạt động của bạn. Chi tiêu bất thường? Chiếc chuông này sẽ sáng lên với cảnh báo tức thì — khỏi cần đi tìm.',
  'tour.help.title': 'Xem lại bất cứ lúc nào',
  'tour.help.body': 'Muốn xem lại hướng dẫn này? Chỉ cần bấm nút “?” này. Bạn sẽ không bao giờ bị lạc.',
  'tour.finish.title': 'Xong rồi đấy!',
  'tour.finish.body':
    'Đó là toàn bộ ứng dụng. Nước đi đầu tiên tốt nhất: thêm một giao dịch và xem tổng quan, ngân sách, phân tích sống dậy. Chúc bạn vui với Vernfy!',
}

const DICTS: Record<Lang, Dict> = { en, vi }

function initialLang(): Lang {
  const saved = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null
  return saved === 'vi' || saved === 'en' ? saved : 'en'
}

type I18nValue = {
  lang: Lang
  setLang: (l: Lang) => void
  t: (key: string, vars?: Record<string, string | number>) => string
}

const I18nContext = createContext<I18nValue | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang)

  const setLang = useCallback((l: Lang) => {
    setLangState(l)
    try {
      localStorage.setItem(STORAGE_KEY, l)
      document.documentElement.lang = l
    } catch {
      // non-fatal (e.g. storage blocked)
    }
  }, [])

  const t = useCallback(
    (key: string, vars?: Record<string, string | number>) => {
      let s = DICTS[lang][key] ?? en[key] ?? key
      if (vars) {
        for (const [k, v] of Object.entries(vars)) s = s.replace(new RegExp(`\\{${k}\\}`, 'g'), String(v))
      }
      return s
    },
    [lang],
  )

  const value = useMemo(() => ({ lang, setLang, t }), [lang, setLang, t])
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used within I18nProvider')
  return ctx
}
