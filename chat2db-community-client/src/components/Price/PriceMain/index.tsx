import { useEffect, useMemo, useRef, useState } from 'react';
import * as QRCode from 'qrcode';
import { Alert, Tabs } from 'antd';
import { useStyles } from './style';
import { PayStatus, PayType } from '@/constants/pricing';
import { CreateOrderResponse, ProductDetail } from '@/typings/pricing';
import pricingServices from '@/service/pricing';
import { formatCurrency, formatPrice, toMajorCurrencyUnit } from '@/utils/price';
import PricingCard from '../PricingCard';
import { SubscriptionType } from '..';
import PayBlock, { BuyPlanParams } from '../PayBlock';
import { useOrgStore } from '@/store/organization';
import { OrganizationType } from '@/typings/enterprise/organization';
import { useUserStore } from '@/store/user';
import { GuideDialogStatus } from '@/components/GuideDialog/type';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import { TabType, SubscriptionType as CircleType } from '../PricePage';
import { isOfflineEnv } from '@/utils/env';
import { normalizeProductDetail } from '../utils';
import { trackPurchase } from '@/utils/googleAds';

export enum InviteCodeStatus {
  None,
  Valid,
  Invalid,
}

interface IProps {
  className?: string;
  tabIndex?: OrganizationType | TabType;
  onTabChange?: (key: OrganizationType | TabType) => void;
  isSinglePage?: boolean;
  productParams?: {
    seats?: number;
    invitationCode?: string;
    productType: TabType;
    subscriptionType: CircleType;
  };
}

const PriceMain = ({ tabIndex, onTabChange, isSinglePage, productParams }: IProps) => {
  const { styles } = useStyles();
  const [productList, setProductList] = useState<ProductDetail[]>();
  const [curPricingCard, setCurPricingCard] = useState<ProductDetail>();
  const [payUrl, setPayUrl] = useState<string>();
  const [curOrderInfo, setOrderInfo] = useState<CreateOrderResponse>();

  const { updatePayStatus, setOpenGuideDialog, setGuideDialogStatus, setConfetti, baseSetting } =
    useGlobalStore((s) => ({
      updatePayStatus: s.updatePayStatus,
      baseSetting: s.baseSetting,
      setOpenGuideDialog: s.setOpenGuideDialog,
      setGuideDialogStatus: s.setGuideDialogStatus,
      setConfetti: s.setConfetti,
    }));

  const { setPricingModalStatus, queryCurUser } = useUserStore((s) => ({
    setPricingModalStatus: s.setPricingModalStatus,
    queryCurUser: s.queryCurUser,
  }));

  const timer = useRef<any>();
  const curOrderIndex = useRef(0);
      // Preserve the order response, including amount and currency,
      // for conversion reporting after polling confirms payment.
      // Use a ref instead of curOrderInfo state because recursive polling captures state from before setState.
  const orderInfoRef = useRef<CreateOrderResponse>();

  useEffect(() => {
    if (!isSinglePage) {
      const { curOrg } = useOrgStore.getState();
      handleTabChange(curOrg?.type || OrganizationType.PERSONAL);
    }

    return () => {
      curOrderIndex.current += 1;
      clearTimeout(timer.current);
    };
  }, []);

  useEffect(() => {
    if (tabIndex) {
      queryProductList();
    }
  }, [tabIndex, baseSetting.language]);

  const queryProductList = async () => {
    if (!tabIndex) return;

    const res = await pricingServices.getProductList({
      version: 2,
      orgType: tabIndex as any,
      language: baseSetting.language,
    });
    const nextProductList = (res || []).map((item) => {
      const normalizedProduct = normalizeProductDetail(item, baseSetting.language);
      return {
        ...normalizedProduct,
        id: normalizedProduct.id,
        title: normalizedProduct.title,
        price: formatPrice(normalizedProduct.price),
        originalPrice: formatPrice(normalizedProduct.originalPrice),
        currencySymbol: formatCurrency(normalizedProduct.currency),
      };
    });
    setProductList(nextProductList);

    if (productParams?.subscriptionType) {
      const product = nextProductList.find((i) => i.subscriptionType === productParams.subscriptionType);
      setCurPricingCard(product);
    } else {
      setCurPricingCard(nextProductList?.[0]);
    }
  };

  const buyPlan = async (props: BuyPlanParams) => {
    const { organizationId, paymentMethod } = props;
    setPayUrl(undefined);
    if (!curPricingCard || !organizationId) return;
    clearTimeout(timer.current);
    const nextOrderIndex = curOrderIndex.current + 1;
    curOrderIndex.current = nextOrderIndex;

    const res = await pricingServices.createOrder({
      id: curPricingCard.id,
      language: baseSetting.language,
      ...props,
    });

    if (nextOrderIndex !== curOrderIndex.current) {
      return;
    }

    if (paymentMethod === PayType.Stripe) {
      setPayUrl(res.paymentUrl || res.url);
    } else if (res.qrCodeUrl) {
      setPayUrl(res.qrCodeUrl);
    } else {
      createQRCode(res.url).then((url) => {
        if (nextOrderIndex === curOrderIndex.current) {
          setPayUrl(url);
        }
      });
    }

    setOrderInfo(res);
    orderInfoRef.current = res;
    polling(res.orderId, nextOrderIndex);
  };

  const polling = (_id: string, orderIndex: number) => {
    pricingServices
      .getOrder({ orderId: _id })
      .then((res) => {
        if (orderIndex !== curOrderIndex.current) {
          return;
        }
        if (res.status === PayStatus.PAY_SUCCESS) {
          setPricingModalStatus(false);
          updatePayStatus(PayStatus.PAY_SUCCESS);
          setOpenGuideDialog(true);
          setGuideDialogStatus(GuideDialogStatus.Subscribed);
          setConfetti(true);
          setTimeout(() => {
            setConfetti(false);
          }, 300);
          clearTimeout(timer.current);

        // Report the overseas Google Ads purchase conversion, converting minor units and deduplicating by orderId.
          const order = orderInfoRef.current;
          trackPurchase({
            orderId: order?.orderId,
            value: toMajorCurrencyUnit(order?.payAmount),
            currency: order?.currency,
          });

          queryCurUser();
          return;
        }

        timer.current = setTimeout(() => {
          polling(_id, orderIndex);
        }, 3000);
      })
      .catch(() => {
        if (orderIndex === curOrderIndex.current) {
          clearTimeout(timer.current);
        }
      });
  };

  const createQRCode: any = (url: string) => {
    return new Promise((resolve) => {
      QRCode.toDataURL(url, {
        type: 'image/jpeg',
        width: 200,
        margin: 1,
      }).then((_url) => {
        resolve(_url);
      });
    });
  };

  const renderCardBlock = () => {
    return (
      <div className={styles.cardBlock}>
        {(productList || []).map((item, index) => (
          <PricingCard
            className={styles.pricingCard}
            onClick={() => {
              setCurPricingCard(item);
            }}
            active={curPricingCard?.id === item.id}
            data={item}
            key={index}
          />
        ))}
      </div>
    );
  };

  const renderPayBlock = () => {
    if (!curPricingCard) {
      return null;
    }

    let tabType = SubscriptionType.PersonalUpdate;
    if (tabIndex === TabType.TEAM) {
      tabType = SubscriptionType.TeamUpdate;
    }
    if (tabIndex === TabType.LOCAL) {
      tabType = SubscriptionType.Offline;
    }

    return (
      <PayBlock
        className={styles.payBlock}
        tabType={tabType}
        curPricingCard={curPricingCard}
        curOrderInfo={curOrderInfo}
        payUrl={payUrl}
        buyPlan={buyPlan}
        invitationCode={productParams?.invitationCode}
        seats={productParams?.seats}
      />
    );
  };

  const renderContent = () => {
    return (
      <div className={styles.innerWrapper}>
        {isOfflineEnv && (
          <Alert
            message={i18n('price.tab.localPro.warning')}
            type="warning"
            showIcon
            style={{ marginBottom: '16px' }}
          />
        )}
        {renderCardBlock()}
        {renderPayBlock()}
      </div>
    );
  };

  const handleTabChange = (key: OrganizationType | TabType) => {
    onTabChange && onTabChange(key);
  };

  const itemList = useMemo(() => {
    if (isSinglePage) {
      return [
        {
          key: TabType.PERSONAL,
          label: i18n('price.tab.personalPro'),
        },
        {
          key: TabType.TEAM,
          label: i18n('price.tab.teamPro'),
        },
        {
          key: TabType.LOCAL,
          label: i18n('price.tab.localPro'),
        },
      ];
    } else {
      return [
        {
          key: OrganizationType.PERSONAL,
          label: i18n('price.tab.personalPro'),
        },
        {
          key: OrganizationType.TEAM,
          label: i18n('price.tab.teamPro'),
        },
      ];
    }
  }, [isSinglePage]);

  return (
    <div className={styles.wrapper}>
      <Tabs
        type="card"
        activeKey={tabIndex}
        onChange={(key) => handleTabChange(key as OrganizationType | TabType)}
        className={styles.tabs}
        animated={{
          inkBar: false,
          tabPane: false,
        }}
        destroyInactiveTabPane
        centered
        items={(itemList || []).map((i) => ({
          key: i.key,
          label: i.label,
          children: renderContent(),
        }))}
      />
    </div>
  );
};

export default PriceMain;
