import DeleteModal from '@/components/DeleteModal';
import Modal from '@/components/Modal/BaseModal';
import SystemErrorMessage from '@/components/SystemErrorMessage';
import UnifiedConfirmationModal from '@/components/UnifiedConfirmationModal';
import UpdateDetection from '@/blocks/UpdateDetection';

const GlobalComponentCommunity = () => {
  return (
    <>
      <SystemErrorMessage />
      <UnifiedConfirmationModal />
      <Modal />
      <DeleteModal />
      <UpdateDetection />
    </>
  );
};

export default GlobalComponentCommunity;
