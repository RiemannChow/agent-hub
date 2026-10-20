package com.riemann.agent.infrastructure.seekdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SeekDB 官方示例数据导入器。
 *
 * <p>在 seekdb 环境下随应用启动运行一次：若 conversation 表为空，
 * 则写入一批“电商客服 / 知识库”风格的对话片段（含向量 embedding），
 * 供混合检索（向量 + 全文 + 标量）演示与验证。</p>
 *
 * <p>embedding 由 {@link LocalEmbedder} 在导入时计算，与查询时的嵌入使用同一算法，
 * 保证向量近邻检索结果一致、可验证。</p>
 */
@Component
@Profile("seekdb")
public class SeekDbDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeekDbDataSeeder.class);

    private final JdbcTemplate jdbc;

    public SeekDbDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 官方示例对话（电商客服 / 知识库）。content 会用于生成向量与全文索引。 */
    private static final List<Conv> DATA = List.of(
        c("c01", "苹果手机激活后七天无理由退货政策",
          "苹果手机支持自签收次日起七天无理由退货，需保持外观完好、配件齐全。激活后若非质量问题一般不支持无理由退，质量问题可走保修。退款原路返回，3-5个工作日到账。"),
        c("c02", "iPhone 电池健康度低于80%更换",
          "iPhone 电池健康度低于80%属于明显衰减，可在保内免费更换电池，过保收取电池服务费。更换后健康度恢复100%，不影响防水。建议到官方授权店处理。"),
        c("c03", "安卓手机系统卡顿清理方法",
          "安卓手机卡顿可清理后台应用、卸载不常用软件、关闭动画特效。若仍卡可尝试恢复出厂设置前先备份。存储空间低于10%也会明显变慢，建议保留余量。"),
        c("c04", "物流配送时效与偏远地区",
          "普通快递大陆地区1-3天送达，偏远地区如新疆西藏需4-7天。下单时可选择加急次日达。大件商品走专线物流，时效以页面承诺为准。"),
        c("c05", "快递丢件破损如何理赔",
          "快递丢件或外包装破损请当面拒收并拍照留证，联系客服发起理赔。保价件按保价金额赔，未保价按运费倍数赔。理赔一般7个工作日内完成。"),
        c("c06", "优惠券领取与使用规则",
          "优惠券在领券中心领取，满减券需达到门槛才能用，限品类券仅指定类目可用。优惠券不能叠加，过期作废。下单时在结算页勾选可用券自动抵扣。"),
        c("c07", "新人专享券与首单优惠",
          "新注册用户可领新人专享券包，首单立减。每个账号仅限一次，需实名认证后使用。首单优惠不与其它活动叠加。"),
        c("c08", "会员等级与积分获取",
          "会员按年消费额分级，购物可得积分，100积分抵1元。签到、评价也能攒积分。积分年底清零，请及时兑换。高级会员享专属客服与包邮。"),
        c("c09", "积分兑换与有效期",
          "积分可在积分商城兑换实物或券，兑换后一般不可撤销。积分自获得起两年有效，到期自动作废。可在我的积分页查询明细。"),
        c("c10", "扫地机器人首次安装设置",
          "扫地机器人首次使用需充满电，下载App配网，建图后规划清扫路线。建议先清空地面线缆。支持定时清扫与禁区设置，App可查看地图。"),
        c("c11", "咖啡机除垢与日常保养",
          "咖啡机建议每冲煮200杯除垢一次，使用专用除垢剂运行清洗程序。日常冲煮后清空渣桶、冲洗冲煮头。长期不用请排干水箱防霉。"),
        c("c12", "美妆产品过敏退换政策",
          "美妆类支持过敏无忧退，使用后过敏可凭照片申请退换，需保留包装。敏感肌建议先购小样试用。开封后非质量问题一般不支持退，但过敏属例外。"),
        c("c13", "口红试色与色号选择",
          "口红选色看肤色，暖皮适合橘调红调，冷皮适合玫调。黄黑皮避开荧光粉。专柜可试色，线上买小样更划算。哑光持久但偏干，滋润款更舒适。"),
        c("c14", "订单地址修改与转寄",
          "订单未发货可在我的订单自助修改地址，已发货需联系快递转寄可能产生费用。大促期间修改受限，建议下单时核对地址。"),
        c("c15", "错拍漏拍如何补单",
          "拍错规格可在未发货前取消重拍，已发货走退换。漏拍商品若仍在上架可直接加购补单，活动价以当下页面为准。"),
        c("c16", "电子发票开具与报销",
          "下单时可填抬头开电子普票，专票需提供税号。电子发票实时开具发送到邮箱，支持多次下载。报销以发票为准，请勿重复申请。"),
        c("c17", "旧手机以旧换新估价",
          "以旧换新在线估价，按型号、成色、能否开机定价。寄出旧机检测后多退少补。换新可叠加补贴券，旧机数据请提前备份清除。"),
        c("c18", "一年质保与延保服务",
          "数码产品享一年质保，非人为损坏免费修。可加购延保至两年。进水、摔损属人为不在保，建议购碎屏险。"),
        c("c19", "价格保护申请退差价",
          "下单后7天内商品降价可申请价保退差价，秒杀、百亿补贴除外。在订单页一键价保，差额原路退回。活动价不支持价保。"),
        c("c20", "秒杀与百亿补贴规则",
          "秒杀整点开抢限量大概率售罄，百亿补贴为平台让利长期低价。两者均不支持价保与优惠券叠加。抢购请提前填好地址。"),
        c("c21", "海外购清关与税费",
          "海外购需清关，个人自用品免税额内不征税，超限缴行邮税。清关时效1-3天，需实名信息一致。禁运品类无法购买。"),
        c("c22", "服饰尺码选择与退换",
          "服饰按尺码表选，体重身高对照更准确。偏胖选大一码。支持七天无理由退换，吊牌勿拆。退换运费险可减免。"),
        c("c23", "客服工作时间与人工入口",
          "在线客服7x24小时机器人接待，人工服务每日9:00-22:00。复杂问题转人工，高峰期排队请耐心。留言会在一个工作日内回复。"),
        c("c24", "支付方式与分期免息",
          "支持微信、支付宝、银行卡。指定商品支持信用卡分期免息，3/6/12期可选。花呗白条可用，逾期影响信用。")
    );

    @Override
    public void run(String... args) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM conversation", Integer.class);
        if (cnt != null && cnt > 0) {
            log.info("[SeekDbDataSeeder] conversation 已存在 {} 条，跳过导入", cnt);
            return;
        }
        for (Conv conv : DATA) {
            jdbc.update(
                    "INSERT INTO conversation "
                            + "(conversation_id, user_id, title, content, embedding, status, updated_at) "
                            + "VALUES (?, 'demo_user', ?, ?, ?, 'ACTIVE', NOW())",
                    conv.id, conv.title, conv.content,
                    LocalEmbedder.toSql(LocalEmbedder.embed(conv.content)));
        }
        log.info("[SeekDbDataSeeder] 已导入 {} 条官方示例对话（含向量 embedding）", DATA.size());
    }

    private static Conv c(String id, String title, String content) {
        return new Conv(id, title, content);
    }

    private record Conv(String id, String title, String content) {
    }
}
