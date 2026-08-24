import DeleteModal from '@/components/DeleteModal';
import Modal from '@/components/Modal/BaseModal';
import SystemErrorMessage from '@/components/SystemErrorMessage';
import UnifiedConfirmationModal from '@/components/UnifiedConfirmationModal';
import AgentConnectorAuthorization from '@/components/AgentConnectorAuthorization';

const GlobalComponentCommunity = () => {
  return (
    <>
      <SystemErrorMessage />
      <UnifiedConfirmationModal />
      <Modal />
      <DeleteModal />
      <AgentConnectorAuthorization />
    </>
  );
};

export default GlobalComponentCommunity;
