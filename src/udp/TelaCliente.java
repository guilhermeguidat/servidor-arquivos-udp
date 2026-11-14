package udp;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public class TelaCliente extends JFrame {

    private final JTextField campoIp = new JTextField("127.0.0.1", 12);
    private final JTextField campoNome = new JTextField("cliente1", 10);
    private final JButton botaoConectar = new JButton("Conectar");
    private final JButton botaoSelecionar = new JButton("Selecionar arquivo");
    private final JButton botaoEnviar = new JButton("Enviar");
    private final JButton botaoBaixar = new JButton("Baixar");
    private final JLabel labelArquivo = new JLabel("nenhum arquivo selecionado");
    private final DefaultListModel<String> modeloLista = new DefaultListModel<>();
    private final JList<String> lista = new JList<>(modeloLista);
    private final JProgressBar barra = new JProgressBar(0, 100);
    private final JTextArea mensagens = new JTextArea(8, 50);

    private Cliente cliente;
    private File arquivoSelecionado;

    public TelaCliente() {
        super("Cliente - Servidor de Arquivos UDP");

        JPanel topo = new JPanel();
        topo.add(new JLabel("IP do servidor:"));
        topo.add(campoIp);
        topo.add(new JLabel("Nome:"));
        topo.add(campoNome);
        topo.add(botaoConectar);

        JPanel botoes = new JPanel();
        botoes.add(botaoSelecionar);
        botoes.add(labelArquivo);
        botoes.add(botaoEnviar);
        botoes.add(botaoBaixar);

        JScrollPane painelLista = new JScrollPane(lista);
        painelLista.setBorder(BorderFactory.createTitledBorder("Arquivos no servidor"));
        JPanel centro = new JPanel(new BorderLayout());
        centro.add(painelLista, BorderLayout.CENTER);
        centro.add(botoes, BorderLayout.SOUTH);

        barra.setStringPainted(true);
        mensagens.setEditable(false);
        JScrollPane painelMensagens = new JScrollPane(mensagens);
        painelMensagens.setBorder(BorderFactory.createTitledBorder("Mensagens"));
        JPanel baixo = new JPanel(new BorderLayout());
        baixo.add(barra, BorderLayout.NORTH);
        baixo.add(painelMensagens, BorderLayout.CENTER);

        add(topo, BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);
        add(baixo, BorderLayout.SOUTH);

        botaoConectar.addActionListener(e -> conectar());
        botaoSelecionar.addActionListener(e -> selecionar());
        botaoEnviar.addActionListener(e -> enviar());
        botaoBaixar.addActionListener(e -> baixar());

        habilitarTransferencia(false);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(720, 520);
        setLocationRelativeTo(null);
    }

    private void conectar() {
        String ip = campoIp.getText().trim();
        String nome = campoNome.getText().trim();
        botaoConectar.setEnabled(false);
        new Thread(() -> {
            try {
                cliente = new Cliente(this, ip);
                cliente.conectar(nome);
                mensagem("conectado ao servidor " + ip);
                SwingUtilities.invokeLater(() -> habilitarTransferencia(true));
            } catch (IOException e) {
                mensagem("servidor indisponivel: " + e.getMessage());
                SwingUtilities.invokeLater(() -> botaoConectar.setEnabled(true));
            }
        }).start();
    }

    private void selecionar() {
        JFileChooser escolha = new JFileChooser();
        if (escolha.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            arquivoSelecionado = escolha.getSelectedFile();
            labelArquivo.setText(arquivoSelecionado.getName());
        }
    }

    private void enviar() {
        if (arquivoSelecionado == null) {
            mensagem("selecione um arquivo antes de enviar");
            return;
        }
        File arquivo = arquivoSelecionado;
        iniciarTransferencia();
        new Thread(() -> {
            try {
                cliente.upload(arquivo);
                mensagem("upload concluido: " + arquivo.getName());
            } catch (IOException e) {
                mensagem("erro no upload: " + e.getMessage());
            }
            SwingUtilities.invokeLater(() -> habilitarTransferencia(true));
        }).start();
    }

    private void baixar() {
        String nome = lista.getSelectedValue();
        if (nome == null) {
            mensagem("selecione um arquivo da lista para baixar");
            return;
        }
        iniciarTransferencia();
        new Thread(() -> {
            try {
                File arquivo = cliente.download(nome);
                mensagem("download concluido: " + arquivo.getAbsolutePath());
            } catch (IOException e) {
                mensagem("erro no download: " + e.getMessage());
            }
            SwingUtilities.invokeLater(() -> habilitarTransferencia(true));
        }).start();
    }

    private void iniciarTransferencia() {
        habilitarTransferencia(false);
        barra.setValue(0);
    }

    private void habilitarTransferencia(boolean ativo) {
        botaoSelecionar.setEnabled(ativo);
        botaoEnviar.setEnabled(ativo);
        botaoBaixar.setEnabled(ativo);
    }

    public void mensagem(String texto) {
        String hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        SwingUtilities.invokeLater(() -> {
            mensagens.append("[" + hora + "] " + texto + "\n");
            mensagens.setCaretPosition(mensagens.getDocument().getLength());
        });
    }

    public void progresso(int valor) {
        SwingUtilities.invokeLater(() -> barra.setValue(valor));
    }

    public void atualizarLista(String mensagem) {
        String[] campos = mensagem.split("\\|");
        SwingUtilities.invokeLater(() -> {
            modeloLista.clear();
            for (int i = 1; i < campos.length; i++) {
                modeloLista.addElement(campos[i]);
            }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TelaCliente().setVisible(true));
    }
}
