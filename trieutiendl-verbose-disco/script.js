document.addEventListener('DOMContentLoaded', () => {
  const buttons = document.querySelectorAll('.primary-btn, .secondary-btn, .ghost-btn');

  buttons.forEach((button) => {
    button.addEventListener('mouseenter', () => {
      button.style.filter = 'saturate(1.1)';
    });

    button.addEventListener('mouseleave', () => {
      button.style.filter = 'none';
    });
  });

  const translations = {
    vi: {
      eyebrow: 'nền tảng vận hành hiện trường',
      heroTitle: 'Giao việc. Xác nhận tình trạng. Đồng bộ mọi cập nhật.',
      heroText: 'HP Telecom giúp đội ngũ hiện trường quản lý công việc nhanh hơn, ghi nhận bằng chứng trực tiếp tại chỗ và gửi cập nhật thời gian thực về trung tâm điều hành mà không bị chậm trễ.',
      primaryCta: 'Đặt lịch demo',
      secondaryCta: 'Xem quy trình',
      metricOne: 'công việc hoàn tất',
      metricTwo: 'độ chính xác lần đầu',
      metricThree: 'trực quan thời gian thực',
      loginBtn: 'Đăng nhập',
      demoBtn: 'Yêu cầu demo'
    },
    en: {
      eyebrow: 'field operations platform',
      heroTitle: 'Assign work. Confirm conditions. Sync every update.',
      heroText: 'HP Telecom gives field teams a faster way to manage jobs, record on-site evidence, and send real-time updates back to the command center without delay.',
      primaryCta: 'Book a demo',
      secondaryCta: 'See workflow',
      metricOne: 'jobs completed',
      metricTwo: 'first-pass accuracy',
      metricThree: 'live visibility',
      loginBtn: 'Login',
      demoBtn: 'Request demo'
    },
    zh: {
      eyebrow: '现场运营平台',
      heroTitle: '分配任务。确认现场状态。同步每一条更新。',
      heroText: 'HP Telecom 帮助现场团队更快管理任务、记录现场证据，并将实时更新同步回指挥中心，减少延迟与沟通损失。',
      primaryCta: '预约演示',
      secondaryCta: '查看流程',
      metricOne: '已完成任务',
      metricTwo: '一次性准确率',
      metricThree: '实时可视化',
      loginBtn: '登录',
      demoBtn: '申请演示'
    }
  };

  const langButtons = document.querySelectorAll('.lang-btn');
  const translateNodes = document.querySelectorAll('[data-i18n]');

  const applyLanguage = (lang) => {
    const dict = translations[lang] || translations.vi;

    translateNodes.forEach((node) => {
      const key = node.dataset.i18n;
      if (dict[key]) {
        node.textContent = dict[key];
      }
    });

    document.documentElement.lang = lang;

    langButtons.forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.lang === lang);
    });
  };

  langButtons.forEach((button) => {
    button.addEventListener('click', () => applyLanguage(button.dataset.lang));
  });

  applyLanguage('vi');
});
